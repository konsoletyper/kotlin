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

package org.jetbrains.kotlin.js.translate.ir

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace

class JsirContext(val bindingTrace: BindingTrace, val generator: (KtExpression) -> JsirExpression) {
    private var resultingStatements = mutableListOf<JsirStatement>()
    private val localVariables = mutableMapOf<VariableDescriptor, JsirVariable>()
    private var declaredLocalVariables = mutableSetOf<VariableDescriptor>()
    private var currentSource: PsiElement? = null
    private val labeledStatements = mutableMapOf<String, JsirLabeled>()

    val bindingContext: BindingContext
        get() = bindingTrace.bindingContext

    var declaration: DeclarationDescriptor? = null
        get
        private set

    var function: FunctionDescriptor? = null
        get
        private set

    var classDescriptor: ClassDescriptor? = null
        get
        private set

    var defaultContinueTarget: JsirLabeled? = null
        get
        private set

    var defaultBreakTarget: JsirLabeled? = null
        get
        private set

    val pool = JsirPool()

    fun append(statement: JsirStatement): JsirContext {
        resultingStatements.add(statement.apply { source = currentSource })
        return this
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
        if (container !is FunctionDescriptor) {
            throw IllegalArgumentException("Can only get accessor for local variables. $descriptor is not a local variable")
        }

        val localVar = localVariables.getOrPut(descriptor) {
            val result = JsirVariable(descriptor.name.asString())
            if (container == function) {
                declaredLocalVariables.add(descriptor)
            }
            result
        }
        return LocalVariableAccessor(localVar)
    }

    fun nestedBlock(body: MutableList<JsirStatement>, action: () -> Unit) {
        val backup = resultingStatements
        try {
            resultingStatements = body
            action()
        }
        finally {
            resultingStatements = backup
        }
    }

    fun nestedFunction(function: FunctionDescriptor, action: () -> Unit) {
        val oldDeclaredVariables = declaredLocalVariables
        val oldFunction = this.function
        val oldDeclaration = declaration
        declaredLocalVariables = mutableSetOf()
        this.function = function
        declaration = function

        try {
            action()
        }
        finally {
            localVariables.keys -= declaredLocalVariables
            declaredLocalVariables = oldDeclaredVariables
            this.function = oldFunction
            declaration = oldDeclaration
        }
    }

    fun nestedLabel(name: String?, statement: JsirLabeled, loop: Boolean, action: () -> Unit) {
        val oldBreakTarget = defaultBreakTarget
        val oldContinueTarget = defaultContinueTarget
        defaultBreakTarget = statement
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

    fun getLabelTarget(psi: KtSimpleNameExpression) {
        labeledStatements[psi.getReferencedName()]!!
    }

    fun generate(expression: KtExpression) = generator(expression)

    inner class LocalVariableAccessor(val localVariable: JsirVariable) : VariableAccessor {
        override fun get() = localVariable.makeReference()

        override fun set(value: JsirExpression) {
            assign(localVariable.makeReference(), value)
        }
    }
}