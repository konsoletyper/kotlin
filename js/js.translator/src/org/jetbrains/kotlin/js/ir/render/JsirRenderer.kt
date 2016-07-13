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
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.js.translate.context.Namer
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils
import org.jetbrains.kotlin.psi.psiUtil.getTextWithLocation
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe

object JsirRenderer {
    fun render(pool: JsirPool): JsProgram {
        val program = JsProgram("")
        program.globalBlock.statements += JsExpressionStatement(render(pool, program))
        return program
    }

    fun render(pool: JsirPool, program: JsProgram): JsFunction {
        val rendererImpl = JsirRendererImpl(pool, program)
        return rendererImpl.render()
    }
}

private class JsirRendererImpl(val pool: JsirPool, val program: JsProgram) {
    val invocationRenderers = listOf(EqualsRenderer(), ToStringRenderer())
    val invocationRendererCache = mutableMapOf<FunctionDescriptor, InvocationRenderer?>()
    val wrapperFunction = JsFunction(program.rootScope, JsBlock(), "wrapper")
    val importsSection = JsBlock()
    val internalNameCache = mutableMapOf<DeclarationDescriptor, JsName>()
    val module = pool.module

    fun render(): JsFunction {
        val initRenderer = StatementRenderer(wrapperFunction)
        for (property in pool.properties.values) {
            wrapperFunction.body.statements += JsVars(JsVars.JsVar(getInternalName(property.declaration)))
            wrapperFunction.body.statements += property.initializerBody.flatMap { initRenderer.renderStatement(it) }
        }

        for (function in pool.functions.values) {
            wrapperFunction.body.statements += FunctionRenderer(function).render().makeStmt()
        }

        wrapperFunction.body.statements.addAll(0, importsSection.statements)
        return wrapperFunction
    }

    private fun getExternalName(descriptor: DeclarationDescriptor): JsExpression {
        if (descriptor is ModuleDescriptor) return JsNameRef(getInternalName(descriptor))
        if (descriptor is PackageFragmentDescriptor && descriptor.name.isSpecial) {
            return getExternalName(DescriptorUtils.getContainingModule(descriptor))
        }

        var currentDescriptor = descriptor
        val descriptors = mutableListOf<DeclarationDescriptor>()
        while (currentDescriptor !is PackageFragmentDescriptor) {
            descriptors += currentDescriptor
            currentDescriptor = currentDescriptor.containingDeclaration!!
        }

        return JsNameRef(getSuggestedName(descriptor), getExternalName(descriptor.containingDeclaration!!))
    }

    private fun getInternalName(descriptor: DeclarationDescriptor): JsName {
        return if (descriptor !in internalNameCache) {
            val name = if (descriptor == descriptor.original) generateInternalName(descriptor) else getInternalName(descriptor.original)
            internalNameCache[descriptor] = name
            if (descriptor !is ModuleDescriptor) {
                val module = DescriptorUtils.getContainingModule(descriptor)
                if (module != pool.module) {
                    importsSection.statements += JsVars(JsVars.JsVar(name, getExternalName(descriptor)))
                }
            }
            name
        }
        else {
            internalNameCache[descriptor]!!
        }
    }

    private fun generateInternalName(descriptor: DeclarationDescriptor): JsName {
        if (descriptor is ModuleDescriptor) {
            return if (descriptor == pool.module) {
                wrapperFunction.scope.declareFreshName("_")
            }
            else {
                val name = if (descriptor.builtIns.builtInsModule == descriptor) {
                    wrapperFunction.scope.declareFreshName("kotlin")
                }
                else {
                    val moduleName = descriptor.name.asString().let { it.substring(1, it.length - 1) }
                    if (moduleName == "kotlin") {
                        return getInternalName(descriptor.builtIns.builtInsModule)
                    }
                    wrapperFunction.scope.declareFreshName(Namer.LOCAL_MODULE_PREFIX + Namer.suggestedModuleName(moduleName))
                }
                wrapperFunction.parameters += JsParameter(name)
                name
            }
        }

        return wrapperFunction.scope.declareFreshName(generateName(descriptor))
    }

    private fun generateName(descriptor: DeclarationDescriptor): String {
        val sb = StringBuilder()
        var currentDescriptor = descriptor
        val descriptors = mutableListOf<DeclarationDescriptor>()
        while (currentDescriptor !is PackageFragmentDescriptor) {
            descriptors += currentDescriptor
            currentDescriptor = currentDescriptor.containingDeclaration!!
        }

        val prefix = currentDescriptor.fqNameUnsafe.pathSegments().asSequence()
                .filter { !it.isSpecial }
                .map { it.asString()[0] }
                .joinToString("")
        sb.append(if (prefix.isNotEmpty()) "${prefix}_" else "")

        sb.append(descriptors.reversed().asSequence()
                .map { getSuggestedName(it) }
                .joinToString("_"))

        return sb.toString()
    }

    private fun getSuggestedName(descriptor: DeclarationDescriptor): String {
        return when (descriptor) {
            is PropertyGetterDescriptor -> "get_" + getSuggestedName(descriptor.correspondingProperty)
            is PropertySetterDescriptor -> "set_" + getSuggestedName(descriptor.correspondingProperty)
            else -> if (descriptor.name.isSpecial) "f" else descriptor.name.asString()
        }
    }

    inner class FunctionRenderer(val function: JsirFunction)
            : StatementRenderer(JsFunction(wrapperFunction.scope, JsBlock(), function.declaration.toString())) {
        fun render(): JsFunction {
            jsFunction.name = getInternalName(function.declaration)
            jsFunction.parameters += function.parameters.map { JsParameter(it.getJsName()) }
            jsFunction.body.statements += function.body.render()

            val declaredVariables = variableNames.keys - function.parameters
            if (declaredVariables.isNotEmpty()) {
                val declarations = JsVars(*declaredVariables.map { JsVars.JsVar(variableNames[it]!!) }.toTypedArray())
                jsFunction.body.statements.add(0, declarations)
            }

            return jsFunction
        }
    }

    inner open class StatementRenderer(val jsFunction: JsFunction) : JsirRenderingContext {
        val labelNames = mutableMapOf<JsirLabeled, JsName>()
        val variableNames = mutableMapOf<JsirVariable, JsName>()
        override var scope: JsScope = jsFunction.scope

        override val module: ModuleDescriptor
            get() = this@JsirRendererImpl.module

        override fun getInternalName(descriptor: DeclarationDescriptor) = this@JsirRendererImpl.getInternalName(descriptor)

        fun getInvocationRenderer(function: FunctionDescriptor) = invocationRendererCache.getOrPut(function) {
            invocationRenderers.firstOrNull { it.isApplicable(function) }
        }

        fun JsirStatement.render(): List<JsStatement> = when (this) {
            is JsirStatement.Assignment -> {
                val left = this.left
                val right = this.right
                if (right is JsirExpression.Invocation && right.isJsCode()) {
                    val (statements, value) = renderJsCode(right)
                    val last = if (left != null) JsAstUtils.assignment(left.render(), value) else value
                    statements + JsExpressionStatement(last)
                }
                else {
                    listOf(JsExpressionStatement(if (left == null) {
                        right.render()
                    }
                    else {
                        JsAstUtils.assignment(left.render(), right.render())
                    }))
                }
            }
            is JsirStatement.Block -> {
                val jsStatements = body.render()
                if (this in labelNames) {
                    listOf(JsLabel(labelNames[this], jsStatements.asStatement()))
                }
                else {
                    jsStatements
                }
            }
            is JsirStatement.Break -> {
                listOf(JsBreak(JsNameRef(target.getJsName())))
            }
            is JsirStatement.Continue -> {
                listOf(JsContinue(JsNameRef(target.getJsName())))
            }
            is JsirStatement.Return -> {
                val value = this.value
                if (value is JsirExpression.Invocation && value.isJsCode()) {
                    val (statements, jsValue) = renderJsCode(value)
                    statements + JsReturn(jsValue)
                }
                else {
                    listOf(JsReturn(value?.render()))
                }
            }
            is JsirStatement.Throw -> {
                listOf(JsThrow(exception.render()))
            }
            is JsirStatement.While -> {
                listOf(JsWhile(condition.render(), body.render().asStatement()).labeled(this))
            }
            is JsirStatement.DoWhile -> {
                listOf(JsDoWhile(condition.render(), body.render().asStatement()).labeled(this))
            }
            is JsirStatement.For -> {
                val jsCondition = condition.render()
                val jsInitList = preAssignments.renderAssignments()
                val jsIncrementList = postAssignments.renderAssignments()
                listOf(JsFor(jsInitList, jsCondition, jsIncrementList, body.render().asStatement()).labeled(this))
            }
            is JsirStatement.If -> {
                val jsCondition = condition.render()
                val jsThen = thenBody.render().asStatement()
                val jsElse = elseBody.render().asStatementOrNull()
                listOf(JsIf(jsCondition, jsThen, jsElse))
            }
            is JsirStatement.Switch -> {
                val jsSwitch = JsSwitch()
                jsSwitch.expression = selector.render()
                jsSwitch.cases += clauses.map {
                    JsCase().apply {
                        caseExpression = it.value.render()
                        statements += it.body.render()
                    }
                }
                jsSwitch.cases += JsDefault().apply {
                    statements += defaultClause.render()
                }
                listOf(jsSwitch.labeled(this))
            }
            is JsirStatement.Try -> {
                val jsTry = JsTry().apply { tryBlock = JsBlock() }
                jsTry.tryBlock.statements += body.render()
                jsTry.catches += catchClauses.map {
                    val jsCatchVar = it.catchVariable.suggestedName ?: "\$tmp"
                    val oldScope = scope
                    scope = JsCatchScope(scope, "catch")
                    val jsCatch = JsCatch(scope, jsCatchVar)
                    variableNames[it.catchVariable] = jsCatch.parameter.name

                    jsCatch.body.statements += body.render()

                    scope = oldScope
                    jsCatch
                }
                if (finallyClause.isNotEmpty()) {
                    jsTry.finallyBlock = JsBlock()
                    jsTry.finallyBlock.statements += finallyClause.render()
                }
                listOf(jsTry)
            }
        }

        private fun JsStatement.labeled(statement: JsirLabeled): JsStatement {
            return if (statement in labelNames) {
                JsLabel(labelNames[statement]!!, this)
            }
            else {
                this
            }
        }

        private fun List<JsirStatement.Assignment>.renderAssignments(): JsExpression? {
            val list = render().map { (it as JsExpressionStatement).expression }
            return if (list.isNotEmpty()) {
                list.drop(1).fold(list.first()) { a, b -> JsBinaryOperation(JsBinaryOperator.COMMA, a, b) }
            }
            else {
                null
            }
        }

        fun JsirLabeled.getJsName(): JsName = labelNames.getOrPut(this) {
            scope.declareFreshName(suggestedLabelName ?: "\$label")
        }

        fun JsirVariable.getJsName(): JsName = variableNames.getOrPut(this) {
            scope.declareFreshName(suggestedName ?: "\$tmp")
        }

        fun renderStatement(statement: JsirStatement) = statement.render()

        override fun renderExpression(expression: JsirExpression) = expression.render()

        fun JsirExpression.render(): JsExpression = when (this) {
            is JsirExpression.Constant -> {
                val value = this.value
                when (value) {
                    is String -> program.getStringLiteral(value)
                    is Byte -> program.getNumberLiteral(value.toInt())
                    is Short -> program.getNumberLiteral(value.toInt())
                    is Int -> program.getNumberLiteral(value)
                    is Float -> program.getNumberLiteral(value.toDouble())
                    is Double -> program.getNumberLiteral(value)
                    is Boolean -> JsLiteral.JsBooleanLiteral.getBoolean(value)
                    else -> error("Unexpected constant value $value")
                }
            }
            is JsirExpression.True -> JsLiteral.TRUE
            is JsirExpression.False -> JsLiteral.FALSE
            is JsirExpression.Null -> JsLiteral.NULL
            is JsirExpression.This -> JsLiteral.THIS
            is JsirExpression.Undefined -> JsPrefixOperation(JsUnaryOperator.VOID, program.getNumberLiteral(0))

            is JsirExpression.VariableReference -> JsNameRef(variable.getJsName())

            is JsirExpression.Binary -> {
                val result: JsExpression = when (operation) {
                    JsirBinaryOperation.ARRAY_GET -> JsArrayAccess(left.render(), right.render())
                    JsirBinaryOperation.COMPARE -> {
                        val kotlinName = getInternalName(module.builtIns.builtInsModule)
                        JsInvocation(JsNameRef("compare", JsNameRef(kotlinName)), left.render(), right.render())
                    }
                    else -> JsBinaryOperation(operation.asJs(), left.render(), right.render())
                }
                result
            }
            is JsirExpression.Concat -> {
                val jsParts = parts.map { it.render() }
                if (jsParts.isNotEmpty()) {
                    jsParts.drop(1).fold(jsParts[0]) { a, b -> JsBinaryOperation(JsBinaryOperator.ADD, a, b) }
                }
                else {
                    program.getStringLiteral("")
                }
            }
            is JsirExpression.Conditional -> {
                JsConditional(condition.render(), thenExpression.render(), elseExpression.render())
            }
            is JsirExpression.Negation -> {
                JsPrefixOperation(JsUnaryOperator.NOT, operand.render())
            }
            is JsirExpression.UnaryMinus -> {
                JsPrefixOperation(JsUnaryOperator.NEG, operand.render())
            }

            is JsirExpression.Invocation -> {
                if (isJsCode()) {
                    val (statements, value) = renderJsCode(this)
                    assert(statements.isEmpty()) { "js() with block statement can't be used as expression at " +
                                                   source?.getTextWithLocation() }
                    value
                }
                else {
                    val invocationRenderer = getInvocationRenderer(function)
                    if (invocationRenderer != null) {
                        invocationRenderer.render(this, this@StatementRenderer)
                    }
                    else {
                        val jsReceiver = receiver?.render()
                        val jsArgs = arguments.map { it.render() }.toTypedArray()
                        if (jsReceiver != null) {
                            if (virtual) {
                                JsInvocation(JsNameRef(getSuggestedName(function), jsReceiver), *jsArgs)
                            }
                            else {
                                val className = getInternalName(function.containingDeclaration)
                                val methodRef = JsNameRef(getSuggestedName(function), JsNameRef("prototype", JsNameRef(className)))
                                JsInvocation(JsNameRef("call", methodRef), *(arrayOf(jsReceiver) + jsArgs))
                            }
                        }
                        else {
                            JsInvocation(JsNameRef(getInternalName(function)), *jsArgs)
                        }
                    }
                }
            }
            is JsirExpression.Application -> {
                JsInvocation(function.render(), *arguments.render().toTypedArray())
            }
            is JsirExpression.NewInstance -> {
                val jsConstructor = JsNameRef(getInternalName(constructor.containingDeclaration))
                val jsArgs = arguments.map { it.render() }
                JsNew(jsConstructor, jsArgs)
            }
            is JsirExpression.FieldAccess -> {
                val field = this.field
                val receiver = this.receiver
                when (field) {
                    is JsirField.Backing -> {
                        if (receiver != null) {
                            JsNameRef(getSuggestedName(field.property), receiver.render())
                        }
                        else {
                            JsNameRef(getInternalName(field.property))
                        }
                    }
                    is JsirField.OuterClass -> {
                        JsNameRef("\$outer", receiver!!.render())
                    }
                }
            }

            is JsirExpression.NewNullPointerExpression -> {
                JsNew(JsNameRef("error"))
            }
            is JsirExpression.ArrayOf -> {
                JsArrayLiteral(elements.render())
            }
            is JsirExpression.ArrayLength -> {
                JsNameRef("length", operand.render())
            }
            is JsirExpression.ToString -> {
                JsInvocation(JsNameRef("toString", value.render()))
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
            JsirBinaryOperation.ARRAY_GET -> error("Can't express $this as binary operation in AST")
        }

        @JvmName("renderStatements")
        fun List<JsirStatement>.render() = flatMap { it.render() }

        fun List<JsStatement>.asStatementOrNull() = when (size) {
            0 -> null
            1 -> this[0]
            else -> JsBlock(this)
        }

        fun List<JsStatement>.asStatement() = asStatementOrNull() ?: JsBlock()

        @JvmName("renderExpressions")
        fun List<JsirExpression>.render() = map { it.render() }
    }
}