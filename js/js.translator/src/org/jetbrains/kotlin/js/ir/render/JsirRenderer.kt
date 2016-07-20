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
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassNotAny
import org.jetbrains.kotlin.resolve.descriptorUtil.isEffectivelyPublicApi
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.utils.singletonOrEmptyList

class JsirRenderer {
    val invocationRenderers = mutableListOf<InvocationRenderer>()
    val classFilters = mutableListOf<(JsirClass) -> Boolean>()
    val functionFilters = mutableListOf<(JsirFunction) -> Boolean>()
    val externalNameContributors = mutableListOf<ExternalNameContributor>()

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
        val rendererImpl = JsirRendererImpl(pool, program).apply {
            invocationRenderers += this@JsirRenderer.invocationRenderers
            classFilters += this@JsirRenderer.classFilters
            functionFilters += this@JsirRenderer.functionFilters
            externalNameContributors += this@JsirRenderer.externalNameContributors
        }
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
    val invocationRenderers = mutableListOf<InvocationRenderer>()
    val classFilters = mutableListOf<(JsirClass) -> Boolean>()
    val functionFilters = mutableListOf<(JsirFunction) -> Boolean>()
    val externalNameContributors = mutableListOf<ExternalNameContributor>()
    val invocationRendererCache = mutableMapOf<FunctionDescriptor, InvocationRenderer?>()
    val wrapperFunction = JsFunction(program.rootScope, JsBlock(), "wrapper")
    val importsSection = mutableListOf<JsStatement>()
    val internalNameCache = mutableMapOf<DeclarationDescriptor, JsName>()
    val rootPackage = Package()
    val module = pool.module
    val moduleNames = mutableListOf<String>()
    val freeVariablesByFunction: Map<JsirFunction, Set<JsirVariable>>
    val descriptorToFunction: Map<FunctionDescriptor, JsirFunction>
    val delegateFieldNames = mutableMapOf<JsirField.Delegate, JsName>()
    val outerFieldNames = mutableMapOf<ClassDescriptor, JsName>()
    val closureFieldNames = mutableMapOf<JsirVariable, JsName>()
    val globalContext = Context(wrapperFunction)

    init {
        val allFunctions = (pool.functions.values.asSequence() + pool.classes.values.asSequence()
                .flatMap { it.functions.values.asSequence() })
        descriptorToFunction = allFunctions.map { it.declaration to it }.toMap()
        val freeVariablesByFunctionPrototype = allFunctions.map { it to mutableSetOf<JsirVariable>() }.toMap()
        fun propagateFreeVariables(function: JsirFunction, variables: Set<JsirVariable>) {
            val existing = freeVariablesByFunctionPrototype[function] ?: return
            val newVariables = variables - existing
            if (newVariables.isNotEmpty()) {
                existing += newVariables
                val outerFunctionDescriptor = function.declaration.containingDeclaration
                if (outerFunctionDescriptor is FunctionDescriptor &&
                    DescriptorUtils.isDescriptorWithLocalVisibility(outerFunctionDescriptor)
                ) {
                    val outerFunction = descriptorToFunction[outerFunctionDescriptor]!!
                    propagateFreeVariables(outerFunction, newVariables)
                }
            }
        }
        for (function in allFunctions) {
            propagateFreeVariables(function, function.collectFreeVariables())
        }
        freeVariablesByFunction = freeVariablesByFunctionPrototype
    }

    fun render(): JsFunction {
        for (property in pool.properties.values) {
            wrapperFunction.body.statements += JsVars(JsVars.JsVar(getInternalName(property.declaration)))
        }

        for (cls in pool.classes.values) {
            if (classFilters.any { !it(cls) }) continue
            renderClass(cls)
        }

        for (function in pool.functions.values) {
            if (functionFilters.any { !it(function) }) continue
            val jsFunction = renderFunction(function)
            jsFunction.name = getInternalName(function.declaration)
            wrapperFunction.body.statements += jsFunction.makeStmt()
        }

        for (statement in pool.initializerBody) {
            wrapperFunction.body.statements += globalContext.render(statement)
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
        val descriptor = cls.declaration
        val constructorName = getInternalName(descriptor)
        val jsConstructor = JsFunction(wrapperFunction.scope, JsBlock(), descriptor.toString())
        jsConstructor.name = constructorName
        wrapperFunction.body.statements += jsConstructor.makeStmt()
        val context = Context(jsConstructor)

        if (descriptor.isInner) {
            outerFieldNames[cls.declaration] = jsConstructor.scope.declareName("outer\$${getSuggestedName(descriptor)}")
        }

        for (delegateField in cls.delegateFields) {
            delegateFieldNames[delegateField] = jsConstructor.scope.declareName("delegate\$${delegateField.suggestedName ?: ""}")
        }

        for (field in cls.closureFields) {
            closureFieldNames[field] = jsConstructor.scope.declareFreshName("closure\$${field.suggestedName ?: "tmp"}")
        }

        val primaryConstructorDescriptor = cls.functions.values.asSequence()
                .map { it.declaration }
                .firstOrNull { it is ConstructorDescriptor && it.isPrimary }
        val parameterVariables = mutableSetOf<JsirVariable>()
        if (primaryConstructorDescriptor != null) {
            val primaryConstructor = cls.functions[primaryConstructorDescriptor]!!
            renderRawFunctionBody(primaryConstructor, context)
            parameterVariables += primaryConstructor.parameters.map { it.variable }
        }

        for (initStatement in cls.initializerBody) {
            jsConstructor.body.statements += context.render(initStatement)
        }

        renderVariableDeclarations(context, parameterVariables)

        val superClassDescriptor = descriptor.getSuperClassNotAny()
        if (superClassDescriptor != null) {
            val superClassName = getInternalName(superClassDescriptor)
            val prototype = JsInvocation(JsNameRef("create", "Object"), makePrototype(superClassName))
            wrapperFunction.body.statements += JsAstUtils.assignment(makePrototype(constructorName), prototype).makeStmt()
            wrapperFunction.body.statements += JsAstUtils.assignment(JsNameRef("constructor", makePrototype(constructorName)),
                                                                     constructorName.makeRef()).makeStmt()
        }
        renderInterfaceDefaultMethods(cls)

        if (descriptor.kind == ClassKind.OBJECT) {
            val instanceRef = JsNameRef("instance", constructorName.makeRef())
            val instance = JsNew(constructorName.makeRef())
            wrapperFunction.body.statements += JsAstUtils.assignment(instanceRef, instance).makeStmt()
        }

        for (function in cls.functions.values) {
            if (functionFilters.any { !it(function) }) continue

            val declaration = function.declaration
            if (declaration is ConstructorDescriptor && declaration.isPrimary) continue

            val lhs = JsNameRef(getNameForMemberFunction(function.declaration), makePrototype(constructorName))
            wrapperFunction.body.statements += JsAstUtils.assignment(lhs, renderFunction(function)).makeStmt()
        }

        closureFieldNames.clear()
        delegateFieldNames.clear()
        outerFieldNames.keys -= cls.declaration
    }

    fun renderInterfaceDefaultMethods(cls: JsirClass) {
        val descriptor = cls.declaration
        val members = descriptor.unsubstitutedMemberScope.getContributedDescriptors(DescriptorKindFilter.CALLABLES)
                .asSequence()
                .filterIsInstance<CallableMemberDescriptor>()
                .filter { it.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE }

        val functions = members.flatMap { member ->
            when (member) {
                is FunctionDescriptor -> sequenceOf(member)
                is VariableDescriptorWithAccessors -> sequenceOf(member.getter, member.setter).filterNotNull()
                else -> emptySequence()
            }
        }

        val constructorName = getInternalName(descriptor)
        for (function in functions) {
            val overriddenFunction = function.overriddenDescriptors.first()
            val overriddenClass = overriddenFunction.containingDeclaration as ClassDescriptor
            if (overriddenClass.kind != ClassKind.INTERFACE) continue

            val thisPrototype = makePrototype(constructorName)
            val overriddenPrototype = makePrototype(getInternalName(overriddenClass))
            val simpleName = getNameForMemberFunction(function)
            wrapperFunction.body.statements += JsAstUtils.assignment(
                    JsNameRef(simpleName, thisPrototype),
                    JsNameRef(simpleName, overriddenPrototype)).makeStmt()
        }
    }

    private fun makePrototype(name: JsName) = JsNameRef("prototype", name.makeRef())

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

            val boundFunction = JsInvocation(JsNameRef("bind", renderRawFunction(function, constructor.scope, freeVariableNames)),
                                             JsLiteral.THIS)
            constructor.body.statements += JsReturn(boundFunction)
            constructor
        }

        return result
    }

    fun renderRawFunction(
            function: JsirFunction, scope: JsScope, freeVariables: Map<JsirVariable, JsName>,
            context: Context = Context(JsFunction(scope, JsBlock(), function.declaration.toString()))
    ): JsFunction {
        val jsFunction = context.jsFunction
        context.renderer.variableNames += freeVariables

        renderRawFunctionBody(function, context)
        renderVariableDeclarations(context, function.parameters.map { it.variable } + freeVariables.keys)

        return jsFunction
    }

    fun renderRawFunctionBody(function: JsirFunction, context: Context) {
        val jsFunction = context.jsFunction
        for (parameter in function.parameters) {
            val parameterName = context.renderer.getJsNameFor(parameter.variable)
            jsFunction.parameters += JsParameter(parameterName)
            if (parameter.defaultBody.isNotEmpty()) {
                val undefined = JsPrefixOperation(JsUnaryOperator.VOID, program.getNumberLiteral(0))
                val emptyParameterCheck = JsAstUtils.equality(parameterName.makeRef(), undefined)
                val emptyParameterFill = JsBlock()
                val emptyParameterIf = JsIf(emptyParameterCheck, emptyParameterFill)
                emptyParameterFill.statements += parameter.defaultBody.flatMap { context.render(it) }
                jsFunction.body.statements += emptyParameterIf
            }
        }
        jsFunction.body.statements += function.body.flatMap { context.render(it) }
    }

    private fun renderVariableDeclarations(context: Context, excluded: Collection<JsirVariable>) {
        val declaredVariables = (context.renderer.variableNames.keys - excluded).distinct()
        if (declaredVariables.isNotEmpty()) {
            val declarations = JsVars(*declaredVariables.map { JsVars.JsVar(context.renderer.variableNames[it]!!) }.toTypedArray())
                    .apply { synthetic = true }
            context.jsFunction.body.statements.add(0, declarations)
        }
    }

    fun getFreeVariables(function: JsirFunction) = freeVariablesByFunction[function].orEmpty()

    fun exportTopLevel() {
        for (function in pool.functions.values) {
            val descriptor = function.declaration
            if (descriptor is VariableAccessorDescriptor || !descriptor.isEffectivelyPublicApi) continue
            val container = descriptor.containingDeclaration as? PackageFragmentDescriptor ?: continue
            if (functionFilters.any { !it(function) }) continue

            val name = ManglingUtils.getSuggestedName(descriptor)
            val jsPackage = getPackage(container.fqName)
            val key = jsPackage.scope.declareName(name).makeRef()
            jsPackage.jsObject.propertyInitializers += JsPropertyInitializer(key, getInternalName(descriptor).makeRef())
        }
        for (cls in pool.classes.values) {
            val descriptor = cls.declaration
            if (descriptor.containingDeclaration.containingDeclaration !is PackageFragmentDescriptor) continue
            if (!descriptor.isEffectivelyPublicApi) continue
            if (classFilters.any { !it(cls) }) continue

            val name = ManglingUtils.getSuggestedName(descriptor)
            val jsPackage = getPackage(DescriptorUtils.getParentOfType(descriptor, PackageFragmentDescriptor::class.java)!!.fqName)
            val key = jsPackage.scope.declareName(name).makeRef()
            jsPackage.jsObject.propertyInitializers += JsPropertyInitializer(key, getInternalName(descriptor).makeRef())
        }
    }

    fun exportTopLevelProperties() {
        for (property in pool.properties.values) {
            val descriptor = property.declaration
            if (!descriptor.isEffectivelyPublicApi) continue
            if (!applyFilter(property)) continue

            val container = descriptor.containingDeclaration as? PackageFragmentDescriptor ?: continue
            val name = ManglingUtils.getSuggestedName(descriptor)

            val jsPackage = getPackageRef(container.fqName)
            val jsLiteral = JsObjectLiteral(true)

            jsLiteral.propertyInitializers += JsPropertyInitializer(JsNameRef("get"), getInternalName(descriptor.getter!!).makeRef())
            val setter = descriptor.setter
            if (setter != null) {
                jsLiteral.propertyInitializers += JsPropertyInitializer(JsNameRef("set"), getInternalName(setter).makeRef())
            }

            val definition = JsInvocation(JsNameRef("defineProperty", "Object"), jsPackage, program.getStringLiteral(name), jsLiteral)
            wrapperFunction.body.statements += definition.makeStmt()
        }
    }

    private fun applyFilter(property: JsirProperty): Boolean {
        val descriptor = property.declaration
        for (accessor in descriptor.getter.singletonOrEmptyList() + descriptor.setter.singletonOrEmptyList()) {
            val function = pool.functions[accessor]
            if (function != null && functionFilters.any { !it(function) }) return false
        }
        return true
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

        val contributedName = externalNameContributors.asSequence()
                .map { it.contribute(descriptor, globalContext) }
                .firstOrNull { it != null }
        if (contributedName != null) return contributedName

        return if (descriptor is PackageFragmentDescriptor) {
            val fqnSequence = descriptor.fqNameUnsafe.pathSegments().asSequence().map { it.asString() }
            val moduleRef = pureFqn(getInternalName(descriptor.module), null)
            fqnSequence.fold(moduleRef) { qualifier, name -> pureFqn(name, qualifier) }
        }
        else {
            return JsNameRef(getSuggestedName(descriptor), getExternalName(descriptor.containingDeclaration!!))
        }
    }

    private fun getNameForMemberFunction(function: FunctionDescriptor): String {
        val overriddenFunction = generateSequence(function.original) { it.overriddenDescriptors.firstOrNull() }.last().original
        return when (overriddenFunction) {
            is PropertyGetterDescriptor -> "get_" + ManglingUtils.getSuggestedName(overriddenFunction.correspondingProperty)
            is PropertySetterDescriptor -> "set_" + ManglingUtils.getSuggestedName(overriddenFunction.correspondingProperty)
            else -> ManglingUtils.getSuggestedName(overriddenFunction)
        }
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

    private fun getInternalName(field: JsirField) = when (field) {
        is JsirField.Backing -> getInternalName(field.property)
        is JsirField.OuterClass -> outerFieldNames[field.classDescriptor]!!
        is JsirField.Delegate -> delegateFieldNames[field]!!
        is JsirField.Closure -> closureFieldNames[field.variable]!!
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

        if (descriptor is FunctionDescriptor && descriptor.containingDeclaration is ClassDescriptor) {
            return wrapperFunction.scope.declareNameUnsafe(getNameForMemberFunction(descriptor.original))
        }
        else if (descriptor is VariableAccessorDescriptor && descriptor.correspondingVariable.containingDeclaration is ClassDescriptor) {
            return wrapperFunction.scope.declareNameUnsafe(getNameForMemberFunction(descriptor.original))
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
            if (currentDescriptor is ClassDescriptor) break
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

    private inner class Context(val jsFunction: JsFunction) : JsirRenderingContext {
        val renderer = StatementRenderer(this)

        override val scope: JsScope
            get() = jsFunction.scope

        override val module: ModuleDescriptor
            get() = this@JsirRendererImpl.module

        override fun render(expression: JsirExpression) = renderer.render(expression)

        override fun render(statement: JsirStatement) = renderer.render(statement)

        override fun getInternalName(descriptor: DeclarationDescriptor) = this@JsirRendererImpl.getInternalName(descriptor)

        override fun getInternalName(field: JsirField) = this@JsirRendererImpl.getInternalName(field)

        override fun getStringLiteral(value: String) = program.getStringLiteral(value)

        override fun getNumberLiteral(value: Int): JsNumberLiteral = program.getNumberLiteral(value)

        override fun getNumberLiteral(value: Double): JsNumberLiteral = program.getNumberLiteral(value)

        override fun getInvocationRenderer(function: FunctionDescriptor) = invocationRendererCache.getOrPut(function) {
            invocationRenderers.firstOrNull { it.isApplicable(function) }
        }

        override fun getFreeVariables(function: JsirFunction) = freeVariablesByFunction[function].orEmpty()

        override fun lookupFunction(descriptor: FunctionDescriptor) = descriptorToFunction[descriptor]
    }
}