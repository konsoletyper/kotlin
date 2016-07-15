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
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.js.ir.analyze.collectFreeVariables
import org.jetbrains.kotlin.js.translate.context.Namer
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils.pureFqn
import org.jetbrains.kotlin.js.translate.utils.ManglingUtils
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.psiUtil.getTextWithLocation
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.resolve.descriptorUtil.isEffectivelyPublicApi

object JsirRenderer {
    fun render(pool: JsirPool): JsProgram {
        val program = JsProgram("")
        val result = render(pool, program)
        val arguments = result.modules.map { makePlainModuleRef(it, program) }

        val invocation = JsInvocation(result.function, arguments)
        val selfName = pool.module.importName
        val assignment = if (Namer.requiresEscaping(selfName)) {
            JsAstUtils.assignment(JsArrayAccess(JsLiteral.THIS, program.getStringLiteral(selfName)), invocation).makeStmt()
        }
        else {
            JsVars(JsVars.JsVar(program.scope.declareName(selfName), invocation))
        }
        program.globalBlock.statements += assignment

        return program
    }

    fun render(pool: JsirPool, program: JsProgram): JsirRenderingResult {
        val rendererImpl = JsirRendererImpl(pool, program)
        val wrapperFunction = rendererImpl.render()
        return JsirRenderingResult(wrapperFunction, rendererImpl.moduleNames)
    }

    private fun makePlainModuleRef(moduleId: String, program: JsProgram): JsExpression {
        return if (Namer.requiresEscaping(moduleId)) {
            JsArrayAccess(JsLiteral.THIS, program.getStringLiteral(moduleId))
        }
        else {
            program.scope.declareName(moduleId).makeRef()
        }
    }
}

private class JsirRendererImpl(val pool: JsirPool, val program: JsProgram) {
    val invocationRenderers = listOf(EqualsRenderer(), ToStringRenderer(), RangeMethodRenderer())
    val invocationRendererCache = mutableMapOf<FunctionDescriptor, InvocationRenderer?>()
    val wrapperFunction = JsFunction(program.rootScope, JsBlock(), "wrapper")
    val importsSection = mutableListOf<JsStatement>()
    val internalNameCache = mutableMapOf<DeclarationDescriptor, JsName>()
    val rootPackage = Package()
    val module = pool.module
    val moduleNames = mutableListOf<String>()
    val freeVariablesByFunction = pool.functions.values.asSequence().map { it to it.collectFreeVariables() }.toMap()

    fun render(): JsFunction {
        val initRenderer = StatementRenderer(wrapperFunction)
        for (property in pool.properties.values) {
            wrapperFunction.body.statements += JsVars(JsVars.JsVar(getInternalName(property.declaration)))
        }

        for (cls in pool.classes.values) {
            renderClass(cls)
        }

        for (function in pool.functions.values) {
            val jsFunction = renderFunction(function)
            jsFunction.name = getInternalName(function.declaration)
            wrapperFunction.body.statements += jsFunction.makeStmt()
        }

        for (statement in pool.initializerBody) {
            wrapperFunction.body.statements += initRenderer.renderStatement(statement)
        }

        wrapperFunction.body.statements.addAll(0, importsSection)

        exportTopLevel()
        val topLevel = getInternalName(module)
        wrapperFunction.body.statements += JsVars(JsVars.JsVar(topLevel, rootPackage.jsObject))
        exportTopLevelProperties()

        val defineModuleRef = JsNameRef("defineModule", getInternalName(module.builtIns.builtInsModule).makeRef())
        val defineModule = JsInvocation(defineModuleRef, program.getStringLiteral(module.importName), topLevel.makeRef())
        wrapperFunction.body.statements += defineModule.makeStmt()

        wrapperFunction.body.statements += JsReturn(topLevel.makeRef())
        return wrapperFunction
    }

    fun renderClass(cls: JsirClass) {
        val constructorName = getInternalName(cls.declaration)
        val jsConstructor = JsFunction(wrapperFunction.scope, JsBlock(), cls.declaration.toString())
        jsConstructor.name = constructorName
        wrapperFunction.body.statements += jsConstructor.makeStmt()
        val renderer = StatementRenderer(jsConstructor)

        val primaryConstructorDescriptor = cls.functions.values.asSequence()
                .map { it.declaration }
                .firstOrNull { it is ConstructorDescriptor && it.isPrimary }
        if (primaryConstructorDescriptor != null) {
            val primaryConstructor = cls.functions[primaryConstructorDescriptor]!!
            renderRawFunction(primaryConstructor, wrapperFunction.scope, emptyMap(), renderer)
        }

        for (initStatement in cls.initializerBody) {
            jsConstructor.body.statements += renderer.renderStatement(initStatement)
        }

        for (function in cls.functions.values) {
            val declaration = function.declaration
            if (declaration is ConstructorDescriptor && declaration.isPrimary) continue

            val prototype = JsNameRef("prototype", constructorName.makeRef())
            val lhs = JsNameRef(getSuggestedName(function.declaration), prototype)
            wrapperFunction.body.statements += JsAstUtils.assignment(lhs, renderFunction(function)).makeStmt()
        }
    }

    fun renderFunction(function: JsirFunction): JsFunction {
        val freeVariables = getFreeVariables(function)

        val result = if (freeVariables.isEmpty()) {
            renderRawFunction(function, wrapperFunction.scope, emptyMap())
        }
        else {
            val constructor = JsFunction(wrapperFunction.scope, JsBlock(), "closure constructor: ${function.declaration}")
            val freeVariableNames = mutableMapOf<JsirVariable, JsName>()
            for (freeVariable in freeVariables) {
                val suggestedName = freeVariable.suggestedName ?: "closure\$tmp"
                val name = constructor.scope.declareFreshName(suggestedName)
                freeVariableNames[freeVariable] = name
                constructor.parameters += JsParameter(name)
            }

            constructor.body.statements += JsReturn(renderRawFunction(function, constructor.scope, freeVariableNames))
            constructor
        }

        return result
    }

    fun renderRawFunction(
            function: JsirFunction, scope: JsScope, freeVariables: Map<JsirVariable, JsName>,
            renderer: StatementRenderer = StatementRenderer(JsFunction(scope, JsBlock(), function.declaration.toString()))
    ): JsFunction {
        val jsFunction = renderer.jsFunction
        renderer.variableNames += freeVariables

        jsFunction.parameters += function.parameters.map { JsParameter(renderer.getJsNameFor(it)) }
        jsFunction.body.statements += function.body.flatMap { renderer.renderStatement(it) }

        val declaredVariables = renderer.variableNames.keys - function.parameters - freeVariables.keys
        if (declaredVariables.isNotEmpty()) {
            val declarations = JsVars(*declaredVariables.map { JsVars.JsVar(renderer.variableNames[it]!!) }.toTypedArray())
                    .apply { synthetic = true }
            jsFunction.body.statements.add(0, declarations)
        }

        return jsFunction
    }

    fun getFreeVariables(function: JsirFunction) = freeVariablesByFunction[function].orEmpty()

    fun exportTopLevel() {
        for (function in pool.functions.keys.filter { it !is VariableAccessorDescriptor && it.isEffectivelyPublicApi }) {
            val container = function.containingDeclaration as? PackageFragmentDescriptor ?: continue
            val name = ManglingUtils.getSuggestedName(function)
            val jsPackage = getPackage(container.fqName)
            val key = jsPackage.scope.declareName(name).makeRef()
            jsPackage.jsObject.propertyInitializers += JsPropertyInitializer(key, getInternalName(function).makeRef())
        }
        for (cls in pool.classes.keys.filter { it.isEffectivelyPublicApi }) {
            val name = ManglingUtils.getSuggestedName(cls)
            val jsPackage = getPackage(DescriptorUtils.getParentOfType(cls, PackageFragmentDescriptor::class.java)!!.fqName)
            val key = jsPackage.scope.declareName(name).makeRef()
            jsPackage.jsObject.propertyInitializers += JsPropertyInitializer(key, getInternalName(cls).makeRef())
        }
    }

    fun exportTopLevelProperties() {
        for (property in pool.properties.keys.filter { it.isEffectivelyPublicApi }) {
            val container = property.containingDeclaration as? PackageFragmentDescriptor ?: continue
            val name = ManglingUtils.getSuggestedName(property)

            val jsPackage = getPackageRef(container.fqName)
            val jsLiteral = JsObjectLiteral(true)

            jsLiteral.propertyInitializers += JsPropertyInitializer(JsNameRef("get"), getInternalName(property.getter!!).makeRef())
            val setter = property.setter
            if (setter != null) {
                jsLiteral.propertyInitializers += JsPropertyInitializer(JsNameRef("set"), getInternalName(setter).makeRef())
            }

            val definition = JsInvocation(JsNameRef("defineProperty", "Object"), jsPackage, program.getStringLiteral(name), jsLiteral)
            wrapperFunction.body.statements += definition.makeStmt()
        }
    }

    fun getPackage(fqn: FqName): Package {
        var currentPackage = rootPackage
        for (segment in fqn.pathSegments()) {
            val next = currentPackage.innerPackages.getOrPut(segment) {
                Package().apply {
                    val name = segment.asString()
                    val key = currentPackage.scope.declareName(name).makeRef()
                    currentPackage.jsObject.propertyInitializers += JsPropertyInitializer(key, jsObject)
                }
            }
            currentPackage = next
        }
        return currentPackage
    }

    fun getPackageRef(fqn: FqName): JsExpression {
        return fqn.pathSegments().fold(getInternalName(module).makeRef()) { qualifier, name -> JsNameRef(name.asString(), qualifier) }
    }

    inner class Package {
        val jsObject = JsObjectLiteral(true)
        val scope = JsObjectScope(program.scope, "")
        val innerPackages = mutableMapOf<Name, Package>()
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
                    importsSection += JsVars(JsVars.JsVar(name, getExternalName(descriptor)))
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
                val (name, importName) = if (descriptor.builtIns.builtInsModule == descriptor) {
                    Pair(wrapperFunction.scope.declareFreshName("kotlin"), "kotlin")
                }
                else {
                    val moduleName = descriptor.name.asString().let { it.substring(1, it.length - 1) }
                    if (moduleName == "kotlin") {
                        return getInternalName(descriptor.builtIns.builtInsModule)
                    }
                    val internalName = wrapperFunction.scope.declareFreshName(Namer.LOCAL_MODULE_PREFIX +
                                                                              Namer.suggestedModuleName(moduleName))
                    Pair(internalName, moduleName)
                }
                wrapperFunction.parameters += JsParameter(name)
                moduleNames += importName
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

        override fun getStringLiteral(value: String) = program.getStringLiteral(value)

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
                    val temporary = left is JsirExpression.VariableReference && left.variable.suggestedName == null
                    val statement = JsExpressionStatement(if (left == null) {
                        right.render()
                    }
                    else {
                        JsAstUtils.assignment(left.render(), right.render()).apply {
                            if (temporary) {
                                synthetic = true
                            }
                        }
                    })
                    if (temporary) {
                        statement.synthetic = true
                    }
                    listOf(statement)
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
                    val returnValue = when (value) {
                        is JsirExpression.Undefined -> null
                        else -> value?.render()
                    }
                    listOf(JsReturn(returnValue))
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
                    val jsCatch = JsCatch(scope, jsCatchVar, JsBlock())
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
                JsLabel(labelNames[statement]!!, this).apply { synthetic = true }
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

        fun getJsNameFor(variable: JsirVariable) = variable.getJsName()

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

            is JsirExpression.VariableReference -> pureFqn(variable.getJsName(), null)

            is JsirExpression.Binary -> {
                val result: JsExpression = when (operation) {
                    JsirBinaryOperation.ARRAY_GET -> JsArrayAccess(left.render(), right.render())
                    JsirBinaryOperation.EQUALS_METHOD -> {
                        JsInvocation(kotlinReference("equals"), left.render(), right.render())
                    }
                    JsirBinaryOperation.COMPARE -> {
                        val kotlinName = getInternalName(module.builtIns.builtInsModule)
                        JsInvocation(pureFqn("compare", pureFqn(kotlinName, null)), left.render(), right.render())
                    }
                    else -> JsBinaryOperation(operation.asJs(), left.render(), right.render())
                }
                result
            }
            is JsirExpression.Unary -> {
                val operation = this.operation
                val result: JsExpression = when (operation) {
                    JsirUnaryOperation.NEGATION -> JsPrefixOperation(JsUnaryOperator.NOT, operand.render())
                    JsirUnaryOperation.MINUS -> JsPrefixOperation(JsUnaryOperator.NEG, operand.render())
                    JsirUnaryOperation.ARRAY_COPY -> JsInvocation(pureFqn("slice", operand.render()))
                    JsirUnaryOperation.TO_STRING -> JsInvocation(JsNameRef("toString", operand.render()))
                    JsirUnaryOperation.ARRAY_LENGTH -> {
                        JsNameRef("length", operand.render()).apply {
                            sideEffects = SideEffectKind.DEPENDS_ON_STATE
                        }
                    }
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
                            val functionName = getNameForMemberFunction(function)
                            if (virtual) {
                                JsInvocation(pureFqn(functionName, jsReceiver), *jsArgs)
                            }
                            else {
                                val className = getInternalName(function.containingDeclaration)
                                val methodRef = pureFqn(functionName, pureFqn("prototype", pureFqn(className, null)))
                                JsInvocation(pureFqn("call", methodRef), *(arrayOf(jsReceiver) + jsArgs))
                            }
                        }
                        else {
                            JsInvocation(pureFqn(getInternalName(function), null), *jsArgs)
                        }
                    }
                }
            }
            is JsirExpression.FunctionReference -> {
                val freeVariables = getFreeVariables(pool.functions[this.function]!!)
                val reference = pureFqn(getInternalName(function), null)
                val result: JsExpression = if (freeVariables.isEmpty()) {
                    reference
                }
                else {
                    val closure = freeVariables.map { it.getJsName().makeRef() }
                    JsInvocation(reference, closure)
                }
                result
            }
            is JsirExpression.Application -> {
                JsInvocation(function.render(), *arguments.render().toTypedArray())
            }
            is JsirExpression.NewInstance -> {
                val jsConstructor = pureFqn(getInternalName(constructor.containingDeclaration), null)
                val jsArgs = arguments.map { it.render() }
                JsNew(jsConstructor, jsArgs)
            }
            is JsirExpression.FieldAccess -> {
                val field = this.field
                val receiver = this.receiver
                when (field) {
                    is JsirField.Backing -> {
                        (if (receiver != null) {
                            JsNameRef(getSuggestedName(field.property), receiver.render())
                        }
                        else {
                            JsNameRef(getInternalName(field.property))
                        }).apply {
                            sideEffects = SideEffectKind.DEPENDS_ON_STATE
                        }
                    }
                    is JsirField.OuterClass -> {
                        JsNameRef("\$outer", receiver!!.render()).apply {
                            sideEffects = SideEffectKind.DEPENDS_ON_STATE
                        }
                    }
                }
            }

            is JsirExpression.NewNullPointerExpression -> {
                JsNew(JsNameRef("error"))
            }
            is JsirExpression.ArrayOf -> {
                JsArrayLiteral(elements.render())
            }

            is JsirExpression.InstanceOf -> renderInstanceOf(value.render(), type)
            is JsirExpression.Cast -> {
                val variable = scope.declareFreshName("\$cast")
                val test = renderInstanceOf(JsAstUtils.assignment(variable.makeRef(), value.render()), type)
                JsConditional(test, variable.makeRef(), JsInvocation(kotlinReference("throwCCE")))
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

        private fun getNameForMemberFunction(function: FunctionDescriptor): String {
            val overriddenFunction = generateSequence(function.original) { it.overriddenDescriptors.firstOrNull() }.last().original
            return when (overriddenFunction) {
                is PropertyGetterDescriptor -> "get_" + ManglingUtils.getSuggestedName(overriddenFunction.correspondingProperty)
                is PropertySetterDescriptor -> "set_" + ManglingUtils.getSuggestedName(overriddenFunction.correspondingProperty)
                else -> ManglingUtils.getSuggestedName(overriddenFunction)
            }
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