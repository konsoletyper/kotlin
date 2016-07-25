/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.ir.render

import com.google.dart.compiler.backend.js.ast.*
import com.google.dart.compiler.backend.js.ast.metadata.SideEffectKind
import com.google.dart.compiler.backend.js.ast.metadata.sideEffects
import com.google.dart.compiler.backend.js.ast.metadata.synthetic
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils.pureFqn
import org.jetbrains.kotlin.psi.psiUtil.getTextWithLocation

class StatementRenderer(val context: JsirRenderingContext) {
    val labelNames = mutableMapOf<JsirLabeled, JsName>()

    fun render(statement: JsirStatement): List<JsStatement> = when (statement) {
        is JsirStatement.Assignment -> {
            val left = statement.left
            val right = statement.right
            if (right is JsirExpression.Invocation && right.isJsCode()) {
                val (statements, value) = context.renderJsCode(right)
                val last = if (left != null) JsAstUtils.assignment(render(left), value) else value
                statements + JsExpressionStatement(last)
            }
            else {
                val temporary = left is JsirExpression.VariableReference && left.variable.suggestedName == null
                val jsStatement = JsExpressionStatement(if (left == null) {
                    render(right)
                }
                else {
                    JsAstUtils.assignment(render(left), render(right)).apply {
                        if (temporary) {
                            synthetic = true
                        }
                    }
                })
                if (temporary) {
                    jsStatement.synthetic = true
                }
                listOf(jsStatement)
            }
        }
        is JsirStatement.Block -> {
            val jsStatements = render(statement.body)
            if (statement in labelNames) {
                listOf(JsLabel(labelNames[statement], jsStatements.asStatement()))
            }
            else {
                jsStatements
            }
        }
        is JsirStatement.Break -> {
            listOf(JsBreak(JsNameRef(statement.target.getJsName())))
        }
        is JsirStatement.Continue -> {
            listOf(JsContinue(JsNameRef(statement.target.getJsName())))
        }
        is JsirStatement.Return -> {
            val value = statement.value
            if (value is JsirExpression.Invocation && value.isJsCode()) {
                val (statements, jsValue) = context.renderJsCode(value)
                statements + JsReturn(jsValue)
            }
            else {
                val returnValue = when (value) {
                    is JsirExpression.Undefined -> null
                    else -> value?.let { render(it) }
                }
                listOf(JsReturn(returnValue))
            }
        }
        is JsirStatement.Throw -> {
            listOf(JsThrow(render(statement.exception)))
        }
        is JsirStatement.While -> {
            listOf(JsWhile(render(statement.condition), render(statement.body).asStatement()).labeled(statement))
        }
        is JsirStatement.DoWhile -> {
            listOf(JsDoWhile(render(statement.condition), render(statement.body).asStatement()).labeled(statement))
        }
        is JsirStatement.For -> {
            val jsCondition = render(statement.condition)
            val jsInitList = statement.preAssignments.renderAssignments()
            val jsIncrementList = statement.postAssignments.renderAssignments()
            listOf(JsFor(jsInitList, jsCondition, jsIncrementList, render(statement.body).asStatement()).labeled(statement))
        }
        is JsirStatement.If -> {
            val jsCondition = render(statement.condition)
            val jsThen = render(statement.thenBody).asStatement()
            val jsElse = render(statement.elseBody).asStatementOrNull()
            listOf(JsIf(jsCondition, jsThen, jsElse))
        }
        is JsirStatement.Switch -> {
            val jsSwitch = JsSwitch()
            jsSwitch.expression = render(statement.selector)
            jsSwitch.cases += statement.clauses.map {
                JsCase().apply {
                    caseExpression = render(it.value)
                    statements += render(it.body)
                }
            }
            jsSwitch.cases += JsDefault().apply {
                statements += render(statement.defaultClause)
            }
            listOf(jsSwitch.labeled(statement))
        }
        is JsirStatement.Try -> {
            val jsTry = JsTry().apply { tryBlock = JsBlock() }
            jsTry.tryBlock.statements += render(statement.body)
            if (statement.catchClauses.isNotEmpty()) {
                val jsCatch = JsCatch(context.scope, "\$tmp", JsBlock())
                val jsCatchVar = jsCatch.parameter.name

                var defaultCatchExists = false
                var appendNextStatement = { statement: JsStatement -> jsCatch.body.statements += statement }
                for (catchClause in statement.catchClauses) {
                    val exceptionType = catchClause.exceptionType
                    if (exceptionType == null) {
                        val jsCatchBody = JsBlock()
                        appendNextStatement(jsCatchBody)
                        jsCatchBody.statements += render(catchClause.body)
                        defaultCatchExists = true
                        break
                    }
                    else {
                        val typeCheck = JsInvocation(context.kotlinReference("isType"),
                                                     JsNameRef(jsCatchVar), context.getInternalName(exceptionType).makeRef())
                        val jsCatchBody = JsBlock()
                        val typeCondition = JsIf(typeCheck, jsCatchBody)
                        jsCatchBody.statements += JsAstUtils.assignment(
                                context.getInternalName(catchClause.catchVariable).makeRef(),
                                jsCatchVar.makeRef()).makeStmt()
                        jsCatchBody.statements += render(catchClause.body)
                        appendNextStatement(typeCondition)
                        appendNextStatement = { typeCondition.elseStatement = it }
                    }
                }
                if (!defaultCatchExists) {
                    appendNextStatement(JsThrow(jsCatchVar.makeRef()))
                }

                jsTry.catches += jsCatch
            }
            if (statement.finallyClause.isNotEmpty()) {
                jsTry.finallyBlock = JsBlock()
                jsTry.finallyBlock.statements += render(statement.finallyClause)
            }
            listOf(jsTry)
        }
    }

    private fun JsStatement.labeled(statement: JsirLabeled): JsStatement {
        return if (statement in labelNames) {
            JsLabel(labelNames[statement]!!, this).apply { synthetic = true }
        }
        else {
            this
        }
    }

    private fun List<JsirStatement.Assignment>.renderAssignments(): JsExpression? {
        val list = render(this).map { (it as JsExpressionStatement).expression }
        return if (list.isNotEmpty()) {
            list.drop(1).fold(list.first()) { a, b -> JsBinaryOperation(JsBinaryOperator.COMMA, a, b) }
        }
        else {
            null
        }
    }

    fun JsirLabeled.getJsName(): JsName = labelNames.getOrPut(this) {
        context.scope.declareFreshName(suggestedLabelName ?: "\$label")
    }

    fun render(expression: JsirExpression): JsExpression = when (expression) {
        is JsirExpression.Constant -> {
            val value = expression.value
            when (value) {
                null -> JsLiteral.NULL
                is String -> context.getStringLiteral(value)
                is Byte -> context.getNumberLiteral(value.toInt())
                is Short -> context.getNumberLiteral(value.toInt())
                is Int -> context.getNumberLiteral(value)
                is Float -> context.getNumberLiteral(value.toDouble())
                is Double -> context.getNumberLiteral(value)
                is Boolean -> JsLiteral.JsBooleanLiteral.getBoolean(value)
                else -> error("Unexpected constant value $value")
            }
        }
        is JsirExpression.This -> JsLiteral.THIS
        is JsirExpression.Undefined -> JsPrefixOperation(JsUnaryOperator.VOID, context.getNumberLiteral(0))

        is JsirExpression.VariableReference -> pureFqn(context.getInternalName(expression.variable), null)

        is JsirExpression.Binary -> {
            val left = render(expression.left)
            val right = render(expression.right)
            val result: JsExpression = when (expression.operation) {
                JsirBinaryOperation.ARRAY_GET -> JsArrayAccess(left, right)
                JsirBinaryOperation.EQUALS_METHOD -> {
                    JsInvocation(context.kotlinReference("equals"), left, right)
                }
                JsirBinaryOperation.COMPARE -> {
                    val kotlinName = context.kotlinName()
                    JsInvocation(pureFqn("compare", pureFqn(kotlinName, null)), left, right)
                }
                else -> JsBinaryOperation(expression.operation.asJs(), left, right)
            }
            result
        }
        is JsirExpression.Unary -> {
            val operand = render(expression.operand)
            val operation = expression.operation
            val result: JsExpression = when (operation) {
                JsirUnaryOperation.NEGATION -> JsPrefixOperation(JsUnaryOperator.NOT, operand)
                JsirUnaryOperation.MINUS -> JsPrefixOperation(JsUnaryOperator.NEG, operand)
                JsirUnaryOperation.ARRAY_COPY -> JsInvocation(pureFqn("slice", operand))
                JsirUnaryOperation.TO_STRING -> JsInvocation(JsNameRef("toString", operand))
                JsirUnaryOperation.ARRAY_LENGTH -> {
                    JsNameRef("length", operand).apply {
                        sideEffects = SideEffectKind.DEPENDS_ON_STATE
                    }
                }
            }
            result
        }
        is JsirExpression.Concat -> {
            val jsParts = expression.parts.map { render(it) }
            if (jsParts.isNotEmpty()) {
                jsParts.drop(1).fold(jsParts[0]) { a, b -> JsBinaryOperation(JsBinaryOperator.ADD, a, b) }
            }
            else {
                context.getStringLiteral("")
            }
        }

        is JsirExpression.Conditional -> {
            JsConditional(render(expression.condition), render(expression.thenExpression), render(expression.elseExpression))
        }

        is JsirExpression.Invocation -> {
            if (expression.isJsCode()) {
                val (statements, value) = context.renderJsCode(expression)
                assert(statements.isEmpty()) { "js() with block statement can't be used as expression at " +
                                               expression.source?.getTextWithLocation() }
                value
            }
            else {
                val function = expression.function
                val invocationRenderer = context.getInvocationRenderer(function)
                if (invocationRenderer != null) {
                    invocationRenderer.render(expression.function, expression.receiver, expression.arguments, expression.virtual, context)
                }
                else {
                    val jsReceiver = expression.receiver?.let { render(it) }
                    val callee = context.lookupFunction(function.original)
                    val freeVariables = callee?.let { context.getFreeVariables(it) }.orEmpty()
                    val jsClosureArgs = freeVariables.map { context.getInternalName(it).makeRef() }
                    val jsArgs = expression.arguments.map { render(it) }.toTypedArray()
                    fun withClosureArgs(expression: JsExpression) = if (jsClosureArgs.isNotEmpty()) {
                        JsInvocation(expression, jsClosureArgs)
                    }
                    else {
                        expression
                    }
                    if (jsReceiver != null) {
                        val functionName = context.getInternalName(function)
                        if (expression.virtual) {
                            JsInvocation(withClosureArgs(pureFqn(functionName, jsReceiver)), *jsArgs)
                        }
                        else {
                            val className = context.getInternalName(function.containingDeclaration)
                            val methodRef = if (function is ConstructorDescriptor) {
                                pureFqn(className, null)
                            }
                            else {
                                pureFqn(functionName, pureFqn("prototype", pureFqn(className, null)))
                            }
                            JsInvocation(pureFqn("call", withClosureArgs(methodRef)), *(arrayOf(jsReceiver) + jsArgs))
                        }
                    }
                    else {
                        JsInvocation(withClosureArgs(pureFqn(context.getInternalName(function), null)), *jsArgs)
                    }
                }
            }
        }

        is JsirExpression.FunctionReference -> {
            val function = expression.function

            val callee = context.lookupFunction(function.original)!!
            val calleeContainer = callee.container
            val freeVariables = context.getFreeVariables(callee).orEmpty()
            val reference: JsExpression = if (calleeContainer is JsirClass) {
                val staticRef = pureFqn("prototype", pureFqn(context.getInternalName(calleeContainer.descriptor), null))
                JsInvocation(pureFqn("bind", pureFqn(context.getInternalName(function), staticRef)), JsLiteral.THIS)
            }
            else {
                pureFqn(context.getInternalName(function), null)
            }

            val result: JsExpression = if (freeVariables.isEmpty()) {
                reference
            }
            else {
                val closure = freeVariables.map { context.getInternalName(it).makeRef() }
                JsInvocation(reference, closure)
            }
            result
        }

        is JsirExpression.ObjectReference -> {
            JsNameRef("instance", context.getInternalName(expression.descriptor).makeRef())
        }

        is JsirExpression.Application -> {
            JsInvocation(render(expression.function), *render(expression.arguments).toTypedArray())
        }

        is JsirExpression.NewInstance -> {
            val invocationRenderer = context.getInvocationRenderer(expression.constructor)
            if (invocationRenderer != null) {
                invocationRenderer.render(expression.constructor, null, expression.arguments, false, context)
            }
            else {
                val jsConstructor = pureFqn(context.getInternalName(expression.constructor.containingDeclaration), null)
                val jsArgs = expression.arguments.map { render(it) }
                JsNew(jsConstructor, jsArgs)
            }
        }

        is JsirExpression.FieldAccess -> {
            val field = expression.field
            val receiver = expression.receiver
            val name = context.getInternalName(field)
            when (field) {
                is JsirField.Backing -> {
                    JsNameRef(name, receiver?.let { render(it) }).apply { sideEffects = SideEffectKind.DEPENDS_ON_STATE }
                }
                is JsirField.OuterClass -> {
                    JsNameRef(name, render(receiver!!)).apply {
                        sideEffects = SideEffectKind.DEPENDS_ON_STATE
                    }
                }
                is JsirField.Closure -> {
                    JsNameRef(name, render(receiver!!))
                }
                is JsirField.Delegate -> {
                    JsNameRef(name, render(receiver!!))
                }
            }
        }

        is JsirExpression.NewNullPointerExpression -> {
            JsNew(JsNameRef("error"))
        }
        is JsirExpression.ArrayOf -> {
            JsArrayLiteral(render(expression.elements))
        }

        is JsirExpression.InstanceOf -> context.renderInstanceOf(render(expression.value), expression.type)
        is JsirExpression.Cast -> {
            val variable = context.scope.declareFreshName("\$cast")
            val test = context.renderInstanceOf(JsAstUtils.assignment(variable.makeRef(), render(expression.value)), expression.type)
            JsConditional(test, variable.makeRef(), JsInvocation(context.kotlinReference("throwCCE")))
        }
    }

    private fun JsirBinaryOperation.asJs() = when (this) {
        JsirBinaryOperation.ADD -> JsBinaryOperator.ADD
        JsirBinaryOperation.SUB -> JsBinaryOperator.SUB
        JsirBinaryOperation.MUL -> JsBinaryOperator.MUL
        JsirBinaryOperation.DIV -> JsBinaryOperator.DIV
        JsirBinaryOperation.REM -> JsBinaryOperator.MOD
        JsirBinaryOperation.AND -> JsBinaryOperator.AND
        JsirBinaryOperation.OR -> JsBinaryOperator.OR
        JsirBinaryOperation.BIT_AND -> JsBinaryOperator.BIT_AND
        JsirBinaryOperation.BIT_OR -> JsBinaryOperator.BIT_OR
        JsirBinaryOperation.BIT_XOR -> JsBinaryOperator.BIT_XOR
        JsirBinaryOperation.SHL -> JsBinaryOperator.SHL
        JsirBinaryOperation.ASHR -> JsBinaryOperator.SHR
        JsirBinaryOperation.LSHR -> JsBinaryOperator.SHRU
        JsirBinaryOperation.EQ -> JsBinaryOperator.EQ
        JsirBinaryOperation.NE -> JsBinaryOperator.NEQ
        JsirBinaryOperation.LT -> JsBinaryOperator.LT
        JsirBinaryOperation.LOE -> JsBinaryOperator.LTE
        JsirBinaryOperation.GT -> JsBinaryOperator.GT
        JsirBinaryOperation.GOE -> JsBinaryOperator.GTE
        JsirBinaryOperation.REF_EQ -> JsBinaryOperator.REF_EQ
        JsirBinaryOperation.REF_NE -> JsBinaryOperator.REF_NEQ

        JsirBinaryOperation.COMPARE,
        JsirBinaryOperation.EQUALS_METHOD,
        JsirBinaryOperation.ARRAY_GET -> error("Can't express $this as binary operation in AST")
    }

    @JvmName("renderStatements")
    fun render(statements: List<JsirStatement>) = statements.flatMap { render(it) }

    fun List<JsStatement>.asStatementOrNull() = when (size) {
        0 -> null
        1 -> this[0]
        else -> JsBlock(this)
    }

    fun List<JsStatement>.asStatement() = asStatementOrNull() ?: JsBlock()

    @JvmName("renderExpressions")
    fun render(expressions: List<JsirExpression>) = expressions.map { render(it) }
}
