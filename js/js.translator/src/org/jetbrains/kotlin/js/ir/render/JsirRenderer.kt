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
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.utils.singletonOrEmptyList

class JsirRenderer {
    val invocationRenderers = mutableListOf<InvocationRenderer>()
    val classFilters = mutableListOf<(JsirClass) -> Boolean>()
    val functionFilters = mutableListOf<(JsirFunction) -> Boolean>()
    val externalNameContributors = mutableListOf<ExternalNameContributor>()

    fun render(module: JsirModule): JsProgram {
        val program = JsProgram("")
        val result = render(module, program)
        val arguments = result.modules.map { makePlainModuleRef(it, program) }

        val invocation = JsInvocation(result.function, arguments)
        val selfName = module.descriptor.importName
        val assignment = if (Namer.requiresEscaping(selfName)) {
            JsAstUtils.assignment(JsArrayAccess(JsLiteral.THIS, program.getStringLiteral(selfName)), invocation).makeStmt()
        }
        else {
            JsVars(JsVars.JsVar(program.scope.declareName(selfName), invocation))
        }
        program.globalBlock.statements += assignment

        return program
    }

    fun render(module: JsirModule, program: JsProgram): JsirRenderingResult {
        val rendererImpl = JsirRendererImpl(module, program).apply {
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

private class JsirRendererImpl(val module: JsirModule, val program: JsProgram) {
    val invocationRenderers = mutableListOf<InvocationRenderer>()
    val classFilters = mutableListOf<(JsirClass) -> Boolean>()
    val functionFilters = mutableListOf<(JsirFunction) -> Boolean>()
    val externalNameContributors = mutableListOf<ExternalNameContributor>()
    val invocationRendererCache = mutableMapOf<FunctionDescriptor, InvocationRenderer?>()
    val wrapperFunction = JsFunction(program.rootScope, JsBlock(), "wrapper")
    val importsSection = mutableListOf<JsStatement>()
    val internalNameCache = mutableMapOf<DeclarationDescriptor, JsName>()
    val rootPackage = Package()
    val moduleNames = mutableListOf<String>()
    val freeVariablesByFunction: Map<JsirFunction, Set<JsirVariable>>
    val descriptorToFunction: Map<FunctionDescriptor, JsirFunction>
    val delegateFieldNames = mutableMapOf<JsirField.Delegate, JsName>()
    val outerFieldNames = mutableMapOf<ClassDescriptor, JsName>()
    val closureFieldNames = mutableMapOf<JsirVariable, JsName>()
    val rootNamespace = Namespace()
    val globalContext = Context(wrapperFunction, rootNamespace)

    init {
        val allFunctions = module.topLevelFunctions + module.classes.values.asSequence().flatMap { it.functions.values.asSequence() }
        descriptorToFunction = allFunctions.map { it.descriptor to it }.toMap()
        val freeVariablesByFunctionPrototype = allFunctions.map { it to mutableSetOf<JsirVariable>() }.toMap()
        fun propagateFreeVariables(function: JsirFunction, variables: Set<JsirVariable>) {
            val existing = freeVariablesByFunctionPrototype[function] ?: return
            val newVariables = variables - existing
            if (newVariables.isNotEmpty()) {
                existing += newVariables
                val outerFunctionDescriptor = function.descriptor.containingDeclaration
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
        for (file in module.files) {
            renderFile(file)
        }

        val topLevel = getInternalName(module.descriptor)
        val topLevelDef = JsVars.JsVar(topLevel)
        wrapperFunction.body.statements += JsVars(topLevelDef)
        exportTopLevel()
        exportTopLevelProperties()
        topLevelDef.initExpression = rootPackage.jsObject

        val defineModuleRef = JsNameRef("defineModule", getInternalName(module.descriptor.builtIns.builtInsModule).makeRef())
        val defineModule = JsInvocation(defineModuleRef, program.getStringLiteral(module.descriptor.importName), topLevel.makeRef())
        wrapperFunction.body.statements += defineModule.makeStmt()

        wrapperFunction.body.statements += JsReturn(topLevel.makeRef())
        return wrapperFunction
    }

    fun renderFile(file: JsirFile) {
        for (property in file.properties.values) {
            wrapperFunction.body.statements += JsVars(JsVars.JsVar(getInternalName(property.descriptor)))
        }

        for (cls in file.classes.values) {
            if (classFilters.any { !it(cls) }) continue
            renderClass(cls)
        }

        val initializerFunctionName = if (file.initializerBody.isNotEmpty()) {
            rootNamespace.generateFreshName("init\$${fileNameToId(file.name)}")
        }
        else {
            ""
        }
        for (function in file.functions.values) {
            if (functionFilters.any { !it(function) }) continue
            val prefix = mutableListOf<JsStatement>()
            if (file.initializerBody.isNotEmpty()) {
                prefix += JsInvocation(pureFqn(initializerFunctionName, null)).makeStmt()
            }

            val jsFunction = renderFunction(function, prefix)
            jsFunction.name = getInternalName(function.descriptor)
            wrapperFunction.body.statements += jsFunction.makeStmt()
        }

        if (file.initializerBody.isNotEmpty()) {
            val initializerFunction = JsFunction(wrapperFunction.scope, JsBlock(), "initializer of ${file.name}")
            initializerFunction.name = program.scope.declareName(initializerFunctionName)
            wrapperFunction.body.statements += initializerFunction.makeStmt()

            val emptyFunction = JsFunction(wrapperFunction.scope, JsBlock(), "empty function")
            initializerFunction.body.statements += JsAstUtils.assignment(initializerFunction.name.makeRef(), emptyFunction).makeStmt()

            val initializerContext = Context(initializerFunction, Namespace(rootNamespace))
            for (statement in file.initializerBody) {
                initializerFunction.body.statements += initializerContext.render(statement)
            }
            renderVariableDeclarations(initializerContext, emptySet())
        }

        wrapperFunction.body.statements.addAll(0, importsSection)
    }

    fun renderClass(cls: JsirClass) {
        val descriptor = cls.descriptor
        val constructorName = getInternalName(descriptor)
        val jsConstructor = JsFunction(wrapperFunction.scope, JsBlock(), descriptor.toString())
        jsConstructor.name = constructorName
        wrapperFunction.body.statements += jsConstructor.makeStmt()
        val context = Context(jsConstructor, Namespace(rootNamespace))

        if (descriptor.isInner) {
            outerFieldNames[cls.descriptor] = program.scope.declareName("outer\$${getSuggestedName(descriptor)}")
        }

        for (delegateField in cls.delegateFields) {
            delegateFieldNames[delegateField] = program.scope.declareName("delegate\$${delegateField.suggestedName ?: ""}")
        }

        for (field in cls.closureFields) {
            closureFieldNames[field] = program.scope.declareName("closure\$${field.suggestedName ?: "tmp"}")
        }

        val primaryConstructorDescriptor = cls.functions.values.asSequence()
                .map { it.descriptor }
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
            renderObjectInstance(descriptor, constructorName)
        }

        for (function in cls.functions.values) {
            if (functionFilters.any { !it(function) }) continue

            val declaration = function.descriptor
            if (declaration is ConstructorDescriptor && declaration.isPrimary) continue

            val lhs = JsNameRef(getInternalName(function.descriptor), makePrototype(constructorName))
            wrapperFunction.body.statements += JsAstUtils.assignment(lhs, renderFunction(function)).makeStmt()
        }

        closureFieldNames.clear()
        delegateFieldNames.clear()
        outerFieldNames.keys -= cls.descriptor
    }

    fun renderObjectInstance(descriptor: DeclarationDescriptor, constructorName: JsName) {
        val instanceRef = JsNameRef("\$instance", constructorName.makeRef())
        wrapperFunction.body.statements += JsAstUtils.assignment(instanceRef, JsLiteral.NULL).makeStmt()

        val instance = JsNew(constructorName.makeRef())
        val instanceFunctionRef = JsNameRef("getInstance", constructorName.makeRef())
        val instanceGetter = JsFunction(wrapperFunction.scope, JsBlock(), "initializer: $descriptor")
        wrapperFunction.body.statements += JsAstUtils.assignment(instanceFunctionRef, instanceGetter).makeStmt()

        val newInstanceGetter = JsFunction(wrapperFunction.scope, JsBlock(), "initializer: $descriptor")
        newInstanceGetter.body.statements += JsReturn(instanceRef.deepCopy())

        instanceGetter.body.statements += JsAstUtils.assignment(instanceFunctionRef.deepCopy(), newInstanceGetter).makeStmt()
        instanceGetter.body.statements += JsAstUtils.assignment(instanceRef.deepCopy(), instance).makeStmt()
        instanceGetter.body.statements += JsReturn(instanceRef.deepCopy())
    }

    fun renderInterfaceDefaultMethods(cls: JsirClass) {
        val descriptor = cls.descriptor
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

    fun renderFunction(function: JsirFunction, prefix: List<JsStatement> = emptyList()): JsFunction {
        val freeVariables = getFreeVariables(function)

        val context = Context(JsFunction(wrapperFunction.scope, JsBlock(), function.descriptor.toString()), Namespace(rootNamespace))
        val result = if (freeVariables.isEmpty()) {
            renderRawFunction(function, wrapperFunction.scope, emptyMap(), context, prefix)
        }
        else {
            val constructor = JsFunction(wrapperFunction.scope, JsBlock(), "closure constructor: ${function.descriptor}")
            val freeVariableNames = mutableMapOf<JsirVariable, JsName>()
            for (freeVariable in freeVariables) {
                val suggestedName = freeVariable.suggestedName ?: "closure\$tmp"
                val name = program.scope.declareName(context.namespace.generateFreshName(suggestedName))
                freeVariableNames[freeVariable] = name
                constructor.parameters += JsParameter(name)
            }

            val rawFunction = renderRawFunction(function, constructor.scope, freeVariableNames, context, prefix)
            val boundFunction = JsInvocation(JsNameRef("bind", rawFunction), JsLiteral.THIS)
            constructor.body.statements += JsReturn(boundFunction)
            constructor
        }

        return result
    }

    fun renderRawFunction(
            function: JsirFunction, scope: JsScope, freeVariables: Map<JsirVariable, JsName>,
            context: Context = Context(JsFunction(scope, JsBlock(), function.descriptor.toString()), Namespace(rootNamespace)),
            prefix: List<JsStatement>
    ): JsFunction {
        val jsFunction = context.jsFunction
        jsFunction.body.statements += prefix
        context.variableNames += freeVariables

        renderRawFunctionBody(function, context)
        renderVariableDeclarations(context, function.parameters.map { it.variable } + freeVariables.keys)

        return jsFunction
    }

    fun renderRawFunctionBody(function: JsirFunction, context: Context) {
        val jsFunction = context.jsFunction
        for (parameter in function.parameters) {
            val parameterName = context.getInternalName(parameter.variable)
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
        val declaredVariables = (context.variableNames.keys - excluded).distinct()
        if (declaredVariables.isNotEmpty()) {
            val declarations = JsVars(*declaredVariables.map { JsVars.JsVar(context.variableNames[it]!!) }.toTypedArray())
                    .apply { synthetic = true }
            context.jsFunction.body.statements.add(0, declarations)
        }
    }

    fun getFreeVariables(function: JsirFunction) = freeVariablesByFunction[function].orEmpty()

    fun exportTopLevel() {
        for (function in module.topLevelFunctions) {
            val descriptor = function.descriptor
            if (descriptor is VariableAccessorDescriptor || !descriptor.isEffectivelyPublicApi) continue
            val container = descriptor.containingDeclaration as? PackageFragmentDescriptor ?: continue
            if (functionFilters.any { !it(function) }) continue

            val name = ManglingUtils.getSuggestedName(descriptor)
            val jsPackage = getPackage(container.fqName)
            val key = jsPackage.scope.declareName(name).makeRef()
            jsPackage.jsObject.propertyInitializers += JsPropertyInitializer(key, getInternalName(descriptor).makeRef())
        }

        classLoop@for (cls in module.classes.values) {
            val descriptor = cls.descriptor
            if (descriptor.containingDeclaration !is PackageFragmentDescriptor) continue
            if (!descriptor.isEffectivelyPublicApi) continue
            if (classFilters.any { !it(cls) }) continue

            val name = ManglingUtils.getSuggestedName(descriptor)
            val container = descriptor.containingDeclaration
            val fqName = when (container) {
                is ClassDescriptor -> container.fqNameSafe
                is PackageFragmentDescriptor -> container.fqName
                else -> continue@classLoop
            }

            if (descriptor.kind == ClassKind.OBJECT) {
                val literal = JsObjectLiteral()
                val getter = JsNameRef("getInstance", getInternalName(descriptor).makeRef())
                literal.propertyInitializers += JsPropertyInitializer(JsNameRef("get"), getter)
                wrapperFunction.body.statements += createDefineProperty(getPackageRef(fqName), name, literal).makeStmt()
            }
            else {
                val jsPackage = getPackage(fqName)
                val key = jsPackage.scope.declareName(name).makeRef()
                jsPackage.jsObject.propertyInitializers += JsPropertyInitializer(key, getInternalName(descriptor).makeRef())
            }
        }
    }

    fun exportTopLevelProperties() {
        for (file in module.files) {
            for (property in file.properties.values) {
                val descriptor = property.descriptor
                if (!descriptor.isEffectivelyPublicApi) continue
                if (!applyFilter(file, property)) continue

                val container = descriptor.containingDeclaration as? PackageFragmentDescriptor ?: continue
                val name = ManglingUtils.getSuggestedName(descriptor)

                val jsPackage = getPackageRef(container.fqName)
                val jsLiteral = JsObjectLiteral(true)

                jsLiteral.propertyInitializers += JsPropertyInitializer(JsNameRef("get"), getInternalName(descriptor.getter!!).makeRef())
                val setter = descriptor.setter
                if (setter != null) {
                    jsLiteral.propertyInitializers += JsPropertyInitializer(JsNameRef("set"), getInternalName(setter).makeRef())
                }

                val definition = createDefineProperty(jsPackage, name, jsLiteral)
                wrapperFunction.body.statements += definition.makeStmt()
            }
        }
    }

    private fun createDefineProperty(key: JsExpression, name: String, literal: JsObjectLiteral): JsInvocation {
        return JsInvocation(JsNameRef("defineProperty", "Object"), key, program.getStringLiteral(name), literal)
    }

    private fun applyFilter(file: JsirFile, property: JsirProperty): Boolean {
        val descriptor = property.descriptor
        for (accessor in descriptor.getter.singletonOrEmptyList() + descriptor.setter.singletonOrEmptyList()) {
            val function = file.functions[accessor]
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
        return fqn.pathSegments().fold(getInternalName(module.descriptor).makeRef()) { qualifier, name ->
            JsNameRef(name.asString(), qualifier)
        }
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
            else -> {
                if (overriddenFunction.name.isSpecial) {
                    val id = when (overriddenFunction) {
                        is FunctionDescriptor -> "lambda"
                        is PropertyGetterDescriptor -> "get_anonymousProperty"
                        is PropertySetterDescriptor -> "set_anonymousProperty"
                        is PropertyDescriptor -> "anonymousProperty"
                        else -> "anonymous"
                    }
                    rootNamespace.generateFreshName(id)
                }
                else {
                    ManglingUtils.getSuggestedName(overriddenFunction)
                }
            }
        }
    }

    private fun getInternalName(descriptor: DeclarationDescriptor): JsName {
        return if (descriptor !in internalNameCache) {
            if (descriptor is CallableDescriptor && descriptor.original != descriptor) {
                val name = getInternalName(descriptor.original)
                internalNameCache[descriptor] = name
                name
            }
            else {
                val (name, import) = if (
                    descriptor is VariableAccessorDescriptor &&
                    descriptor.correspondingVariable.containingDeclaration is ClassDescriptor
                ) {
                    Pair(program.scope.declareName(getNameForMemberFunction(descriptor.original)), false)
                }
                else if (descriptor is FunctionDescriptor && isDeclaredInClass(descriptor)) {
                    Pair(program.scope.declareName(getNameForMemberFunction(descriptor.original)), false)
                }
                else {
                    Pair(generateInternalName(descriptor.original), true)
                }
                internalNameCache[descriptor] = name
                if (descriptor !is ModuleDescriptor && import) {
                    val module = DescriptorUtils.getContainingModule(descriptor)
                    if (module != this.module.descriptor) {
                        importsSection += JsVars(JsVars.JsVar(name, getExternalName(descriptor)))
                    }
                }
                name
            }
        }
        else {
            internalNameCache[descriptor]!!
        }
    }

    private fun isDeclaredInClass(function: FunctionDescriptor): Boolean {
        return generateSequence(function as CallableMemberDescriptor) { it.containingDeclaration as? CallableMemberDescriptor }
                .last().containingDeclaration is ClassDescriptor
    }

    private fun getInternalName(field: JsirField) = when (field) {
        is JsirField.Backing -> getInternalName(field.property)
        is JsirField.OuterClass -> outerFieldNames[field.classDescriptor]!!
        is JsirField.Delegate -> delegateFieldNames[field]!!
        is JsirField.Closure -> closureFieldNames[field.variable]!!
    }

    private fun generateInternalName(descriptor: DeclarationDescriptor): JsName {
        if (descriptor is ModuleDescriptor) {
            return if (descriptor == module.descriptor) {
                program.scope.declareName(rootNamespace.generateFreshName("_"))
            }
            else {
                val (nameString, importName) = if (descriptor.builtIns.builtInsModule == descriptor) {
                    Pair(rootNamespace.generateFreshName("kotlin"), "kotlin")
                }
                else {
                    val moduleName = descriptor.name.asString().let { it.substring(1, it.length - 1) }
                    if (moduleName == "kotlin") {
                        return getInternalName(descriptor.builtIns.builtInsModule)
                    }
                    val internalName = rootNamespace.generateFreshName(Namer.LOCAL_MODULE_PREFIX + Namer.suggestedModuleName(moduleName))
                    Pair(internalName, moduleName)
                }

                val name = program.scope.declareName(nameString)
                wrapperFunction.parameters += JsParameter(name)
                moduleNames += importName
                name
            }
        }

        return program.scope.declareName(rootNamespace.generateFreshName(generateName(descriptor)))
    }

    private fun generateName(descriptor: DeclarationDescriptor): String {
                val sb = StringBuilder()
        var currentDescriptor = descriptor
        val descriptors = mutableListOf<DeclarationDescriptor>()
        while (currentDescriptor !is PackageFragmentDescriptor) {
            descriptors += currentDescriptor
            currentDescriptor = currentDescriptor.containingDeclaration!!
            if (descriptor !is ClassDescriptor && currentDescriptor is ClassDescriptor) break
        }

        val prefix = currentDescriptor.fqNameUnsafe.pathSegments().asSequence()
                .filter { !it.isSpecial }
                .map { it.asString()[0] }
                .joinToString("")
        sb.append(if (prefix.isNotEmpty()) "${prefix}_" else "")

        sb.append(descriptors.reversed().asSequence()
                .map { getSuggestedName(it) }
                .joinToString("\$"))

        return sb.toString()
    }

    private fun getSuggestedName(descriptor: DeclarationDescriptor): String {
        return when (descriptor) {
            is PropertyGetterDescriptor -> "get_" + getSuggestedName(descriptor.correspondingProperty)
            is PropertySetterDescriptor -> "set_" + getSuggestedName(descriptor.correspondingProperty)
            else -> if (descriptor.name.isSpecial) "f" else descriptor.name.asString()
        }
    }

    private fun fileNameToId(name: String): String {
        val sb = StringBuilder()
        for (c in name) {
            when (c) {
                in '0'..'9',
                in 'a'..'z',
                in 'A'..'Z' -> sb.append(c)
                else -> sb.append('_')
            }
        }
        return sb.toString()
    }

    private inner class Context(val jsFunction: JsFunction, val namespace: Namespace) : JsirRenderingContext {
        val variableNames = mutableMapOf<JsirVariable, JsName>()
        val renderer = StatementRenderer(this)

        override val scope: JsScope
            get() = program.scope

        override val module: ModuleDescriptor
            get() = this@JsirRendererImpl.module.descriptor

        override fun render(expression: JsirExpression) = renderer.render(expression)

        override fun render(statement: JsirStatement) = renderer.render(statement)

        override fun getInternalName(descriptor: DeclarationDescriptor) = this@JsirRendererImpl.getInternalName(descriptor)

        override fun getInternalName(field: JsirField) = this@JsirRendererImpl.getInternalName(field)

        override fun getInternalName(variable: JsirVariable): JsName = variableNames.getOrPut(variable) {
            program.scope.declareName(namespace.generateFreshName(variable.suggestedName ?: "\$tmp"))
        }

        override fun getTemporaryName(template: String) = program.scope.declareName(namespace.generateFreshName(template))

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