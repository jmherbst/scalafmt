package org.scalafmt.rewrite

import scala.meta._

import org.scalafmt.config.FilterMatcher
import org.scalafmt.config.RewriteSettings
import org.scalafmt.util.InfixApp
import org.scalafmt.util.TreeOps

object AvoidInfix extends RewriteFactory {

  override def hasChanged(v1: RewriteSettings, v2: RewriteSettings): Boolean =
    v2.neverInfix ne v1.neverInfix

  override def create(implicit ctx: RewriteCtx): RewriteSession =
    new AvoidInfix

  def getMatcher(ctx: RewriteCtx): FilterMatcher =
    ctx.style.rewrite.neverInfix.matcher

  def getMatcherIfEnabled(ctx: RewriteCtx): Option[FilterMatcher] =
    if (ctx.style.rewrite.rules.contains(AvoidInfix)) Some(getMatcher(ctx))
    else None

}

class AvoidInfix(implicit ctx: RewriteCtx) extends RewriteSession {

  private val matcher = AvoidInfix.getMatcher(ctx)

  // In a perfect world, we could just use
  // Tree.transform {
  //   case t: Term.ApplyInfix => Term.Apply(Term.Select(t.lhs, t.op), t.args)
  // }
  // and be done with it. However, until transform becomes token aware (see https://github.com/scalameta/scalameta/pull/457)
  // we will do these dangerous rewritings by hand.

  override def rewrite(tree: Tree): Unit =
    tree match {
      case Term.ApplyInfix(lhs, op, _, args)
          if InfixApp.isLeftAssoc(op.value) && matcher.matches(op.value) =>
        val builder = Seq.newBuilder[TokenPatch]

        val opHead = op.tokens.head
        builder += TokenPatch.AddLeft(opHead, ".", keepTok = true)

        if (TreeOps.isSeqSingle(args)) { // otherwise, definitely enclosed
          val rhs = args.head
          rhs.tokens.headOption.foreach { head =>
            val last = args.head.tokens.last
            val opLast = op.tokens.last
            if (!ctx.isMatching(head, last)) {
              if (RewriteCtx.hasPlaceholder(args.head)) return
              builder += TokenPatch.AddRight(opLast, "(", keepTok = true)
              builder += TokenPatch.AddRight(last, ")", keepTok = true)
            } else {
              // move delimiter (before comment or newline)
              builder +=
                TokenPatch.AddRight(opLast, head.syntax, keepTok = true)
              builder += TokenPatch.Remove(head)
            }
          }
        }

        val shouldWrapLhs = lhs match {
          case Term.ApplyInfix(_, o, _, _) if !matcher.matches(o.value) && ! {
                val head = lhs.tokens.head
                head.is[Token.LeftParen] &&
                ctx.getMatchingOpt(head).contains(lhs.tokens.last)
              } =>
            if (RewriteCtx.hasPlaceholder(lhs)) return
            true
          case _: Term.Eta => true // foo _ compose bar => (foo _).compose(bar)
          case _ => false
        }
        if (shouldWrapLhs) {
          builder += TokenPatch.AddLeft(lhs.tokens.head, "(", keepTok = true)
          builder += TokenPatch.AddRight(lhs.tokens.last, ")", keepTok = true)
        }

        // remove parens if enclosed
        for {
          parent <- tree.parent
          if !parent.is[Term.ApplyInfix] && !parent.is[Term.Apply]
          if ctx.style.rewrite.rules.contains(RedundantParens)
          infixTokens = tree.tokens
          head <- infixTokens.headOption if head.is[Token.LeftParen]
          last <- infixTokens.lastOption if last.is[Token.RightParen]
          if ctx.isMatching(head, last)
        } yield {
          builder += TokenPatch.Remove(head)
          builder += TokenPatch.Remove(last)
        }

        ctx.addPatchSet(builder.result(): _*)

      case _ =>
    }

}
