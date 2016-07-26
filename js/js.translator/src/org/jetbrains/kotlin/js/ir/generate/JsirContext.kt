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

package org.jetbrains.kotlin.js.ir.generate

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace

class JsirContext(val bindingTrace: BindingTrace, module: ModuleDescriptor, val generator: (KtExpression) -> JsirExpression) {
    private var resultingStatements = mutableListOf<JsirStatement>()
    private val localVariables = mutableMapOf<VariableDescriptor, JsirVariable>()
    private var declaredLocalVariables = mutableSetOf<VariableDescriptor>()
    private var currentSource: PsiElement? = null
    private val labeledStatements = mutableMapOf<String, JsirLabeled>()
    private val continueReplacementsImpl = mutableMapOf<JsirLabeled, JsirLabeled>()
    private val extensionParametersImpl = mutableMapOf<FunctionDescriptor, JsirVariable?>()
    val module = JsirModule(module)

    val bindingContext: BindingContext
        get() = bindingTrace.bindingContext

    var declaration: DeclarationDescriptor? = null
        get
        private set

    var function: JsirFunction? = null
        get
        private set

    private val functions = mutableMapOf<FunctionDescriptor, JsirFunction>()

    var variableContainer: JsirVariableContainer? = null
        get
        private set

    var classDescriptor: JsirClass? = null
        get
        private set

    var file: JsirFile? = null
        get
        private set

    var container: JsirContainer? = null
        get
        private set

    var initializerBody: MutableList<JsirStatement>? = null
        get
        private set

    var defaultContinueTarget: JsirLabeled? = null
        get
        private set

    var defaultBreakTarget: JsirLabeled? = null
        get
        private set

    val continueReplacements: Map<JsirLabeled, JsirLabeled>
        get() = continueReplacementsImpl

    val extensionParameters: Map<FunctionDescriptor, JsirVariable?>
        get() = extensionParametersImpl

    var outerParameter: JsirVariable? = null
        get
        private set

    fun append(statement: JsirStatement): JsirContext {
        if (resultingStatements.isNotEmpty() && isTerminalStatement(resultingStatements.last())) {
            return this
        }

        resultingStatements.add(statement.apply { source = currentSource })
        return this
    }

    fun appendAll(statements: List<JsirStatement>): JsirContext {
        for (statement in statements) {
            append(statement)
        }
        return this
    }

    private fun isTerminalStatement(statement: JsirStatement) = when (statement) {
        is JsirStatement.Return,
        is JsirStatement.Break,
        is JsirStatement.Continue,
        is JsirStatement.Throw -> true
        else -> false
    }

    fun <T> withSource(source: PsiElement?, action: () -> T): T {
        val backup = currentSource
        return try {
            currentSource = source
            action()
        }
        finally {
            currentSource = backup
        }
    }

    fun getVariable(descriptor: VariableDescriptor): LocalVariableAccessor {
        val container = descriptor.containingDeclaration

        val localVar = localVariables.getOrPut(descriptor) {
            val name = if (!descriptor.name.isSpecial) descriptor.name.asString() else null
            val result = variableContainer!!.createVariable(descriptor.isVar, name)
            if (container == function?.descriptor) {
                declaredLocalVariables.add(descriptor)
            }
            result
        }
        return LocalVariableAccessor(localVar)
    }

    fun nestedBlock(body: MutableList<JsirStatement>, action: () -> Unit) {
        val exit = enterBlock(body)
        try {
            action()
        }
        finally {
            exit()
        }
    }

    fun enterBlock(body: MutableList<JsirStatement>): () -> Unit {
        val backup = resultingStatements
        resultingStatements = body
        return { resultingStatements = backup }
    }

    fun nestedFunction(function: JsirFunction, extensionParameter: JsirVariable?, outerParameter: JsirVariable?, action: () -> Unit) {
        val oldFunction = this.function
        val oldDeclaration = declaration
        val oldVariableContainer = variableContainer
        val oldOuterParameter = outerParameter
        this.function = function
        this.outerParameter = outerParameter
        declaration = function.descriptor
        extensionParametersImpl[function.descriptor] = extensionParameter
        variableContainer = function.variableContainer
        val functionWasInMap = function.descriptor in functions.keys
        if (!functionWasInMap) {
            functions[function.descriptor] = function
        }

        try {
            nestedVariableScope(action)
        }
        finally {
            this.function = oldFunction
            declaration = oldDeclaration
            extensionParametersImpl.keys -= function.descriptor
            variableContainer = oldVariableContainer
            if (functionWasInMap) {
                functions.keys -= function.descriptor
            }
            this.outerParameter = oldOuterParameter
        }
    }

    fun nestedVariableContainer(variableContainer: JsirVariableContainer, action: () -> Unit) {
        val oldVariableContainer = this.variableContainer
        this.variableContainer = variableContainer
        try {
            action()
        }
        finally {
            this.variableContainer = oldVariableContainer
        }
    }

    fun nestedFile(file: JsirFile, action: () -> Unit) {
        val oldContainer = container
        val oldFile = file
        val oldVariableContainer = variableContainer
        container = file
        this.file = file
        variableContainer = file.variableContainer
        try {
            withInitializerBody(file.initializerBody, action)
        }
        finally {
            container = oldContainer
            this.file = oldFile
            variableContainer = oldVariableContainer
        }
    }

    fun withInitializerBody(initializerBody: MutableList<JsirStatement>, action: () -> Unit) {
        val oldInitializerBody = this.initializerBody
        this.initializerBody = initializerBody
        try {
            action()
        }
        finally {
            this.initializerBody = oldInitializerBody
        }
    }

    fun nestedVariableScope(action: () -> Unit) {
        val oldDeclaredVariables = declaredLocalVariables
        declaredLocalVariables = mutableSetOf()
        try {
            action()
        }
        finally {
            localVariables.keys -= declaredLocalVariables
            declaredLocalVariables = oldDeclaredVariables
        }
    }

    fun nestedClass(cls: JsirClass, action: () -> Unit) {
        val oldClass = classDescriptor
        val oldDeclaration = declaration
        val oldContainer = container
        classDescriptor = cls
        declaration = cls.descriptor
        container = cls

        try {
            action()
        }
        finally {
            classDescriptor = oldClass
            declaration = oldDeclaration
            container = oldContainer
        }
    }

    fun nestedLabel(name: String?, statement: JsirLabeled, defaultBreak: Boolean, loop: Boolean, action: () -> Unit) {
        val oldBreakTarget = defaultBreakTarget
        val oldContinueTarget = defaultContinueTarget

        if (defaultBreak) {
            defaultBreakTarget = statement
        }
        if (loop) {
            defaultContinueTarget = statement
        }

        val oldTarget = labeledStatements[name]
        if (name != null) {
            labeledStatements[name] = statement
        }

        try {
            action()
        }
        finally {
            defaultBreakTarget = oldBreakTarget
            defaultContinueTarget = oldContinueTarget
            if (name != null) {
                labeledStatements.keys -= name
                if (oldTarget != null) {
                    labeledStatements[name] = oldTarget
                }
                else {
                    labeledStatements.keys -= name
                }
            }
        }
    }

    fun withContinueReplacement(statement: JsirLabeled, replacement: JsirLabeled, action: () -> Unit) {
        continueReplacementsImpl[statement] = replacement
        try {
            action()
        }
        finally {
            continueReplacementsImpl.keys -= statement
        }
    }

    fun getLabelTarget(psi: KtSimpleNameExpression) = labeledStatements[psi.getReferencedName()]!!

    fun generate(expression: KtExpression) = try {
        generator(expression)
    }
    catch (e: JsirGenerationException) {
        throw e
    }
    catch (e: Throwable) {
        throw JsirGenerationException(expression, e)
    }

    inner class LocalVariableAccessor(val localVariable: JsirVariable) : VariableAccessor {
        override fun get() = makeReference()

        override fun set(value: JsirExpression) {
            assign(makeReference(), value)
        }

        private fun makeReference() = JsirExpression.VariableReference(localVariable)
    }
}