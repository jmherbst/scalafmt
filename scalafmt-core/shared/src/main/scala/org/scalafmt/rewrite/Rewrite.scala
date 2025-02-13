package org.scalafmt.rewrite

import scala.annotation.tailrec
import scala.collection.mutable

import metaconfig.ConfCodecEx
import scala.meta._
import scala.meta.Input.VirtualFile
import scala.meta.tokens.Token.LF
import scala.meta.transversers.SimpleTraverser

import org.scalafmt.config.ReaderUtil
import org.scalafmt.config.RewriteSettings
import org.scalafmt.config.ScalafmtConfig
import org.scalafmt.util.{TokenOps, TokenTraverser, TreeOps, Trivia, Whitespace}

case class RewriteCtx(
    style: ScalafmtConfig,
    fileName: String,
    tree: Tree
) {
  implicit val dialect = style.dialect

  private val patchBuilder = mutable.Map.empty[(Int, Int), TokenPatch]

  val tokens = tree.tokens
  val tokenTraverser = new TokenTraverser(tokens, fileName)
  val matchingParens = TreeOps.getMatchingParentheses(tokens)

  @inline def getMatching(a: Token): Token =
    matchingParens(TokenOps.hash(a))

  @inline def getMatchingOpt(a: Token): Option[Token] =
    matchingParens.get(TokenOps.hash(a))

  @inline def isMatching(a: Token, b: Token) =
    getMatchingOpt(a).contains(b)

  @inline def getIndex(token: Token) = tokenTraverser.getIndex(token)

  def applyPatches: String =
    tokens.toIterator
      .map(x => patchBuilder.get(x.start -> x.end).fold(x.syntax)(_.newTok))
      .mkString

  def addPatchSet(patches: TokenPatch*): Unit =
    if (!patches.exists(x => tokenTraverser.isExcluded(x.tok)))
      patches.foreach { patch =>
        val key = (patch.tok.start, patch.tok.end)
        val value = patchBuilder.get(key) match {
          case Some(prev) => Patch.merge(prev, patch)
          case None => patch
        }
        patchBuilder.update(key, value)
      }

  def onlyWhitespaceBefore(index: Int): Boolean =
    tokenTraverser
      .findAtOrBefore(index - 1)(RewriteCtx.isLFSkipWhitespace)
      .isDefined

  def findNonWhitespaceWith(
      f: (Token => Option[Boolean]) => Option[Token]
  ): Option[(Token, Option[LF])] = {
    var lf: Option[LF] = None
    val nonWs = f {
      case t: LF =>
        if (lf.nonEmpty) Some(false)
        else { lf = Some(t); None }
      case Whitespace() => None
      case _ => Some(true)
    }
    nonWs.map((_, lf))
  }

  // end is inclusive
  def removeLFToAvoidEmptyLine(
      beg: Int,
      end: Int
  )(implicit builder: Rewrite.PatchBuilder): Unit =
    if (onlyWhitespaceBefore(beg))
      tokenTraverser
        .findAtOrAfter(end + 1)(RewriteCtx.isLFSkipWhitespace)
        .map(TokenPatch.Remove)
        .foreach(builder += _)

  // special case for Select which might contain a space instead of dot
  def isPrefixExpr(expr: Tree): Boolean =
    RewriteCtx.isSimpleExprOr(expr) {
      case t: Term.Select if !RewriteCtx.hasPlaceholder(expr) =>
        val maybeDot = tokenTraverser.findBefore(t.name.tokens.head) {
          case Trivia() => None
          case t => Some(t.is[Token.Dot])
        }
        maybeDot.isDefined
    }

}

trait Rewrite

abstract class RewriteFactory extends Rewrite {
  def create(implicit ctx: RewriteCtx): RewriteSession
  def hasChanged(v1: RewriteSettings, v2: RewriteSettings): Boolean = false
}

abstract class RewriteSession(implicit ctx: RewriteCtx) {
  def rewrite(tree: Tree): Unit
  implicit val dialect = ctx.dialect
}

object RewriteSession {
  final class None(implicit ctx: RewriteCtx) extends RewriteSession {
    def rewrite(tree: Tree): Unit = {}
  }
}

object Rewrite {

  type PatchBuilder =
    scala.collection.mutable.Builder[TokenPatch, Seq[TokenPatch]]

  private val rewrites = Seq[sourcecode.Text[Rewrite]](
    RedundantBraces,
    RedundantParens,
    Imports,
    SortImports,
    AsciiSortImports,
    PreferCurlyFors,
    ExpandImportSelectors,
    AvoidInfix,
    SortModifiers
  )

  implicit val reader: ConfCodecEx[Rewrite] = ReaderUtil.oneOf(rewrites: _*)

  val name2rewrite: Map[String, Rewrite] =
    rewrites.view.map(x => x.source -> x.value).toMap
  val rewrite2name: Map[Rewrite, String] = name2rewrite.map(_.swap)
  val available = name2rewrite.keys.mkString(", ")

  val default: Seq[Rewrite] = name2rewrite.values.toSeq

  def apply(input: VirtualFile, style: ScalafmtConfig): VirtualFile = {
    val rewrites = style.rewrite.rewriteFactoryRules
    if (rewrites.isEmpty) {
      input
    } else {
      style.runner.parse(input) match {
        case Parsed.Success(ast) =>
          val ctx = RewriteCtx(style, input.path, ast)
          val rewriteSessions = rewrites.map(_.create(ctx)).toList
          val traverser = new SimpleTraverser {
            override def apply(tree: Tree): Unit = {
              rewriteSessions.foreach(_.rewrite(tree))
              super.apply(tree)
            }
          }
          traverser(ast)
          input.copy(value = ctx.applyPatches)
        case _ => input
      }
    }
  }

}

object RewriteCtx {

  // this is a helper function to be used with the token traverser
  // finds a newline not blocked by any non-whitespace characters
  private def isLFSkipWhitespace(token: Token): Option[Boolean] =
    token match {
      case _: LF => Some(true)
      case Whitespace() => None
      case _ => Some(false)
    }

  // https://www.scala-lang.org/files/archive/spec/2.13/06-expressions.html#prefix-infix-and-postfix-operations
  def isSimpleExprOr(
      expr: Tree
  )(orElse: PartialFunction[Tree, Boolean]): Boolean =
    expr match {
      case _: Lit | _: Name | _: Term.Interpolate => true
      case _: Term.New | _: Term.NewAnonymous => !hasPlaceholder(expr)
      case _: Term.Apply | _: Term.ApplyUnary => !hasPlaceholder(expr)
      case _ => orElse.applyOrElse(expr, (_: Tree) => false)
    }

  @inline
  def isPostfixExpr(expr: Tree)(implicit style: ScalafmtConfig): Boolean =
    isSimpleExprOr(expr) {
      case _: Term.Select | _: Term.ApplyInfix =>
        !hasPlaceholder(expr)
      case _: Term.Match if style.dialect.allowMatchAsOperator =>
        !hasPlaceholder(expr)
    }

  def hasPlaceholder(expr: Tree): Boolean = {
    val queue = new mutable.Queue[Tree]
    queue += expr
    @tailrec
    def iter: Boolean =
      queue.nonEmpty && {
        queue.dequeue() match {
          case _: Term.Placeholder => true
          case t: Term.ApplyInfix =>
            queue ++= (t.lhs +: t.args)
            iter
          case t: Term.Apply =>
            queue ++= (t.fun +: t.args)
            iter
          case t: Term.Select =>
            queue += t.qual
            iter
          case _ =>
            iter
        }
      }
    iter
  }

}
