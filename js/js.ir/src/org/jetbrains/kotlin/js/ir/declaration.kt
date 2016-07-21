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

package org.jetbrains.kotlin.js.ir

import org.jetbrains.kotlin.descriptors.*

sealed class JsirContainer {
    val functions: Map<FunctionDescriptor, JsirFunction>
        get() = mutableFunctions
    val properties: Map<VariableDescriptorWithAccessors, JsirProperty>
        get() = mutableProperties

    val initializerBody = mutableListOf<JsirStatement>()

    internal val mutableFunctions = mutableMapOf<FunctionDescriptor, JsirFunction>()
    internal val mutableProperties = mutableMapOf<VariableDescriptorWithAccessors, JsirProperty>()
}

class JsirFunction(val declaration: FunctionDescriptor, val container: JsirContainer, val static: Boolean) {
    val parameters = mutableListOf<JsirParameter>()
    val body = mutableListOf<JsirStatement>()

    init {
        container.mutableFunctions[declaration] = this
    }

    fun delete() {
        if (container.mutableFunctions[declaration] == this) {
            container.mutableFunctions.keys -= declaration
        }
    }
}

class JsirParameter(val variable: JsirVariable) {
    val defaultBody = mutableListOf<JsirStatement>()
}

class JsirProperty(val declaration: VariableDescriptorWithAccessors, val container: JsirContainer) {
    init {
        container.mutableProperties[declaration] = this
    }

    fun delete() {
        if (container.mutableProperties[declaration] == this) {
            container.mutableProperties.keys -= declaration
        }
    }
}

class JsirClass(val declaration: ClassDescriptor, val pool: JsirPool) : JsirContainer() {
    var hasOuterProperty = false

    val closureFields = mutableSetOf<JsirVariable>()

    val delegateFields = mutableSetOf<JsirField.Delegate>()

    init {
        pool.mutableClasses[declaration] = this
    }

    fun delete() {
        if (pool.mutableClasses[declaration] == this) {
            pool.mutableClasses.keys -= declaration
        }
    }
}

class JsirPool(val module: ModuleDescriptor) : JsirContainer() {
    val classes: Map<ClassDescriptor, JsirClass>
        get() = mutableClasses

    internal val mutableClasses = mutableMapOf<ClassDescriptor, JsirClass>()
}

class JsirVariable(val suggestedName: String? = null)

fun JsirVariable.makeReference() = JsirExpression.VariableReference(this, false)