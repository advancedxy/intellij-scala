package org.jetbrains.plugins.scala.debugger

import java.util
import java.util.Collections

import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.evaluation.{EvaluationContext, TextWithImports, TextWithImportsImpl}
import com.intellij.debugger.engine.{DebuggerUtils, FrameExtraVariablesProvider}
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiElement, PsiNamedElement, ResolveState}
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.codeInsight.template.util.VariablesCompletionProcessor
import org.jetbrains.plugins.scala.debugger.evaluation.{ScalaCodeFragmentFactory, ScalaEvaluatorBuilder, ScalaEvaluatorBuilderUtil}
import org.jetbrains.plugins.scala.debugger.filters.ScalaDebuggerSettings
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil.inNameContext
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.{ScCaseClause, ScTypedPattern, ScWildcardPattern}
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScCatchBlock, ScEnumerator, ScForStatement, ScGenerator}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScClassParameter
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunctionDefinition, ScPatternDefinition}
import org.jetbrains.plugins.scala.lang.resolve.{ScalaResolveResult, StdKinds}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

/**
* Nikolay.Tropin
* 2014-12-04
*/
class ScalaFrameExtraVariablesProvider extends FrameExtraVariablesProvider {
  override def isAvailable(sourcePosition: SourcePosition, evaluationContext: EvaluationContext): Boolean = {
    ScalaDebuggerSettings.getInstance().SHOW_VARIABLES_FROM_OUTER_SCOPES &&
            sourcePosition.getFile.getLanguage == ScalaLanguage.Instance
  }

  override def collectVariables(sourcePosition: SourcePosition,
                                evaluationContext: EvaluationContext,
                                alreadyCollected: util.Set[String]): util.Set[TextWithImports] = {

    val method = Try(evaluationContext.getFrameProxy.location().method()).toOption
    if (method.isEmpty || DebuggerUtils.isSynthetic(method.get)) return Collections.emptySet()

    val result: mutable.SortedSet[String] = inReadAction {
      val element = sourcePosition.getElementAt

      if (element == null) mutable.SortedSet()
      else getVisibleVariables(element, evaluationContext, alreadyCollected)
    }
    result.map(toTextWithImports).asJava
  }

  private def getVisibleVariables(elem: PsiElement, evaluationContext: EvaluationContext, alreadyCollected: util.Set[String]) = {
    val completionProcessor = new CollectingProcessor(elem)
    PsiTreeUtil.treeWalkUp(completionProcessor, elem, null, ResolveState.initial)
    val sorted = mutable.SortedSet()(Ordering.by[ScalaResolveResult, Int](_.getElement.getTextRange.getStartOffset))
    completionProcessor.candidates
      .filter(srr => !alreadyCollected.contains(srr.name))
      .filter(canEvaluate(_, elem, evaluationContext)).foreach(sorted += _)
    sorted.map(_.name)
  }

  private def toTextWithImports(s: String) = {
    val xExpr = new XExpressionImpl(s, ScalaLanguage.Instance, "")
    TextWithImportsImpl.fromXExpression(xExpr)
  }

  private def canEvaluate(srr: ScalaResolveResult, place: PsiElement, evaluationContext: EvaluationContext) = {
    srr.getElement match {
      case _: ScWildcardPattern => false
      case tp: ScTypedPattern if tp.name == "_" => false
      case cp: ScClassParameter if !cp.isEffectiveVal =>
        def notInThisClass(elem: PsiElement) = {
          elem != null && !PsiTreeUtil.isAncestor(cp.containingClass, elem, true)
        }
        val funDef = PsiTreeUtil.getParentOfType(place, classOf[ScFunctionDefinition])
        val lazyVal = PsiTreeUtil.getParentOfType(place, classOf[ScPatternDefinition]) match {
          case null => null
          case LazyVal(lzy) => lzy
          case _  => null
        }
        notInThisClass(funDef) || notInThisClass(lazyVal)
      case named if ScalaEvaluatorBuilderUtil.isNotUsedEnumerator(named, place) => false
      case inNameContext(cc: ScCaseClause) if isInCatchBlock(cc) => false //cannot evaluate catched exceptions in scala
      case inNameContext(LazyVal(_)) => false //don't add lazy vals as they can be computed too early
      case named if generatorNotFromBody(named, place) => tryEvaluate(named.name, place, evaluationContext).isSuccess
      case named if notUsedInCurrentClass(named, place) => tryEvaluate(named.name, place, evaluationContext).isSuccess
      case _ => true
    }
  }

  private def isInCatchBlock(cc: ScCaseClause): Boolean = {
    cc.parents.take(3).exists(_.isInstanceOf[ScCatchBlock])
  }

  private def tryEvaluate(name: String, place: PsiElement, evaluationContext: EvaluationContext): Try[AnyRef] = {
    Try {
      val twi = toTextWithImports(name)
      val codeFragment = new ScalaCodeFragmentFactory().createCodeFragment(twi, place, evaluationContext.getProject)
      val location = evaluationContext.getFrameProxy.location()
      val sourcePosition = new ScalaPositionManager(evaluationContext.getDebugProcess).getSourcePosition(location)
      val evaluator = ScalaEvaluatorBuilder.build(codeFragment, sourcePosition)
      evaluator.evaluate(evaluationContext)
    }
  }

  private def notUsedInCurrentClass(named: PsiElement, place: PsiElement) = {
    val contextClass = ScalaEvaluatorBuilderUtil.getContextClass(place, strict = false)
    val containingClass = ScalaEvaluatorBuilderUtil.getContextClass(named)
    contextClass != containingClass && ReferencesSearch.search(named, new LocalSearchScope(contextClass)).findFirst() == null
  }

  private def generatorNotFromBody(named: PsiNamedElement, place: PsiElement): Boolean = {
    val forStmt = ScalaPsiUtil.nameContext(named) match {
      case nc @ (_: ScEnumerator | _: ScGenerator) =>
        Option(PsiTreeUtil.getParentOfType(nc, classOf[ScForStatement]))
      case _ => None
    }
    forStmt.flatMap(_.enumerators).exists(_.isAncestorOf(named)) && forStmt.flatMap(_.body).exists(!_.isAncestorOf(place))
  }
}

private class CollectingProcessor(element: PsiElement) extends VariablesCompletionProcessor(StdKinds.valuesRef) {

  val containingFile = element.getContainingFile
  val startOffset = element.getTextRange.getStartOffset

  override def execute(element: PsiElement, state: ResolveState): Boolean = {
    val result = super.execute(element, state)

    candidatesSet.foreach(rr => if (!isBeforeAndInSameFile(rr)) candidatesSet -= rr)
    result
  }

  private def isBeforeAndInSameFile(candidate: ScalaResolveResult): Boolean = {
    val candElem = candidate.getElement
    val candElemContext = ScalaPsiUtil.nameContext(candElem) match {
      case cc: ScCaseClause => cc.pattern.getOrElse(cc)
      case other => other
    }
    candElem.getContainingFile == containingFile && candElemContext.getTextRange.getEndOffset < startOffset
  }
}