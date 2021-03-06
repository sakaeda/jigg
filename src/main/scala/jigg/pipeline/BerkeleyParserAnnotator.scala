package jigg.pipeline

/*
 Copyright 2013-2015 Hiroshi Noji

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/


import jigg.util.PropertiesUtil
import jigg.util.XMLUtil.RichNode
import jigg.util.{IOUtil, LogUtil}

import java.io.IOException
import java.util.Properties
import java.util.{List => JList}

import scala.xml._
import scala.collection.mutable.HashMap
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters._

import edu.berkeley.nlp.PCFGLA.{
  BerkeleyParser, CoarseToFineMaxRuleParser, ParserData, TreeAnnotations}

import edu.berkeley.nlp.syntax.Tree
import edu.berkeley.nlp.util.{MyMethod, Numberer}

trait BerkeleyParserAnnotator extends AnnotatingSentencesInParallel {
  def threshold = 1.0 // this value is used without explanation in original BerkeleyParser.java

  def keepFunctionLabels = true // but probably the model does not output function labels.

  def defaultGrFilePath = "jigg-models/berkeleyparser/eng_sm6.gr"

  @Prop(gloss = "Grammar file") var grFileName = ""
  @Prop(gloss = "Use annotated POS (by another annotator)") var usePOS =
    BerkeleyParserAnnotator.defaultUsePOS
  @Prop(gloss = "Compute viterbi derivation instead of max-rule tree (Default: max-rule)") var viterbi = false
  @Prop(gloss = "Set thresholds for accuracy. (Default: set thresholds for efficiency)") var accurate = false
  @Prop(gloss = "Use variational rule score approximation instead of max-rule (Default: false)") var variational = false

  readProps()

  override def description = s"""${super.description}

  A wrapper for Berkeley parser. The feature is that this wrapper is implemented to be
  thread-safe. To do this, the wrapper keeps many parser instances (the number can be
  specified by customizing -nThreads).

  The path to the model file can be changed by setting -${name}.grFileName.

  If -${name}.usePOS is true, the annotator assumes the POS annotation is already
  performed, and the parser builds a tree based on the assigned POS tags.
  Otherwise, the parser performs joint inference of POS tagging and parsing, which
  is the default behavior.
"""

  override def init() = {
    localAnnotators // init here, to output help message without loading
  }

  trait LocalBerkeleyAnnotator extends LocalAnnotator {

    type Parser = BerkeleyParserAnnotator.Parser

    val parser: Parser = mkParser()

    protected def mkParser(): Parser = new Parser {
      val internalParser = mkInternalParser()
      def parse(sentence: JList[String], pos: JList[String]): Tree[String] = {
        val deriv = internalParser.getBestConstrainedParse(sentence, pos, null)
        TreeAnnotations.unAnnotateTree(deriv, keepFunctionLabels)
      }
    }

    protected def safeParse(sentence: JList[String], pos: JList[String]): Tree[String] = {
      val tree = parser.parse(sentence, pos)
      if (tree.size == 1 && !sentence.isEmpty) throw new AnnotationError("Failed to parse.")
      else tree
    }
  }

  private def mkInternalParser(): CoarseToFineMaxRuleParser = {
    val gr = if (grFileName == "") defaultGrFilePath else grFileName
    val parserData =
      LogUtil.track(s"Loading berkeleyparser model from ${gr} ... ") { loadParserData() }

    val grammar = parserData.getGrammar
    val lexicon = parserData.getLexicon
    Numberer.setNumberers(parserData.getNumbs())

    new CoarseToFineMaxRuleParser(
      grammar,
      lexicon,
      threshold,
      -1,
      viterbi,
      false, // substates are not supported
      false, // scores are not supported
      accurate,
      variational,
      true, // copied from BerkeleyParser.java
      true) // copied from BerkeleyParser.java
  }

  private def loadParserData(): ParserData = {
    import IOUtil._
    def safeOpen(path: String) =
      try Some(openBinIn(defaultGrFilePath, gzipped=true))
      catch { case e: Throwable => None }

    val in = grFileName match {
      case "" =>
        // First try to search from the class loader
        try Some(openResourceAsObjectStream(defaultGrFilePath, gzipped=true))
        // then search for the current path on the file system
        catch { case e: IOException => safeOpen(defaultGrFilePath) }
      case path => safeOpen(path)
    }
    val p: Option[ParserData] = in flatMap { a =>
      try Some(a.readObject.asInstanceOf[ParserData])
      catch { case e: Throwable => None }
    }
    p match {
      case Some(pData) => pData
      case None => // error
        argumentError("grFileName", s"""Failed to load grammar from ${grFileName}.
Is "jigg-models.jar" included in the class path?

Or you can download the English model file from:
  https://github.com/slavpetrov/berkeleyparser/raw/master/eng_sm6.gr
and specify it by e.g., "-${name}.grFileName eng_sm6.gr"
""")
    }
  }

  def treeToNode(tree: Tree[String], tokenSeq: Seq[Node], sentenceId: String): Node = {
    val trees = tree.getChildren.asScala // ignore root node

    var id = -1
    var tokIdx = -1

    def nextId = { id += 1; sentenceId + "_berksp" + id }
    def nextTokId = { tokIdx += 1; tokenSeq(tokIdx) \@ "id" }

    val addId = new MyMethod[Tree[String], (String, String)] {
      def call(t: Tree[String]): (String, String) = t match {
        case t if t.isPreTerminal => (t.getLabel, nextTokId) // preterminal points to token id; this is ok since `transformNodes` always proceeds left-to-right manner
        case t if t.isLeaf => (t.getLabel, "")
        case t => (t.getLabel, nextId)
      }
    }

    val treesWithId: Seq[Tree[(String, String)]] =
      trees map { _ transformNodesUsingNode addId }
    val root = treesWithId map (_.getLabel._2) mkString " "

    val spans = new ArrayBuffer[Node]

    def traverseTree[A, B](t: Tree[A])(f: Tree[A]=>B): Unit = {
      f(t)
      t.getChildren.asScala foreach (traverseTree(_)(f))
    }

    treesWithId foreach { treeWithId =>
      traverseTree(treeWithId) { t =>
        val label = t.getLabel
        val children = t.getChildren.asScala map (_.getLabel._2) mkString " "

        if (!t.isLeaf && !t.isPreTerminal)
          spans += <span id={ label._2 } symbol={ label._1 } children={ children } />
      }
    }
    <parse annotators={ name } root={ root }>{ spans }</parse>
  }
}

class BerkeleyParserAnnotatorFromToken(
  override val name: String,
  override val props: Properties) extends BerkeleyParserAnnotator {

  def mkLocalAnnotator = new LocalTokenBerkeleyAnnotator

  class LocalTokenBerkeleyAnnotator extends LocalBerkeleyAnnotator {
    def newSentenceAnnotation(sentence: Node) = {

      def addPOS(tokenSeq: NodeSeq, tree: Tree[String]): NodeSeq = {
        val preterminals = tree.getPreTerminals.asScala
        (0 until tokenSeq.size) map { i =>
          tokenSeq(i) addAttribute ("pos", preterminals(i).getLabel)
        }
      }

      val tokens = (sentence \ "tokens").head
      val tokenSeq = tokens \ "token"

      val tree = safeParse(tokenSeq.map(_ \@ "form").asJava, null)

      val taggedSeq = addPOS(tokenSeq, tree)

      val newTokens = {
        val nameAdded = tokens addAnnotatorName name
        nameAdded replaceChild taggedSeq
      }
      val parseNode = treeToNode(tree, taggedSeq, sentence \@ "id")

      // TODO: this may be customized with props?
      sentence addOrOverwriteChild Seq(newTokens, parseNode)
    }
  }

  override def requires = Set(Requirement.Tokenize)
  override def requirementsSatisfied = Set(Requirement.POS, Requirement.Parse)
}

class BerkeleyParserAnnotatorFromPOS(
  override val name: String,
  override val props: Properties) extends BerkeleyParserAnnotator {

  def mkLocalAnnotator = new LocalPOSBerkeleyAnnotator

  class LocalPOSBerkeleyAnnotator extends LocalBerkeleyAnnotator {
    def newSentenceAnnotation(sentence: Node) = {
      val tokens = sentence \ "tokens"
      val tokenSeq = tokens \ "token"
      val posSeq = tokenSeq.map(_ \@ "pos").asJava

      val tree = safeParse(tokenSeq.map(_+"").asJava, posSeq)

      val parseNode = treeToNode(tree, tokenSeq, sentence \@ "id")

      // TODO: is it ok to override in default?
      sentence addOrOverwriteChild Seq(parseNode)
    }
  }

  override def requires = Set(Requirement.POS)
  override def requirementsSatisfied = Set(Requirement.Parse)
}

object BerkeleyParserAnnotator extends AnnotatorCompanion[BerkeleyParserAnnotator] {

  def defaultUsePOS = false

  override def fromProps(name: String, props: Properties) = {
    val usepos = name + ".usePOS"
    PropertiesUtil.findProperty(usepos, props) getOrElse (defaultUsePOS+"") match {
      case "true" => new BerkeleyParserAnnotatorFromPOS(name, props)
      case "false" => new BerkeleyParserAnnotatorFromToken(name, props)
    }
  }

  /** Parser abstracts how a sentence is parsed with a Berkely parser object.
    *
    * Can be stubed for unit-testing. */
  trait Parser {
    def parse(sentence: JList[String], pos: JList[String]): Tree[String]
  }
}
