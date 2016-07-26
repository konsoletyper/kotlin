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

    internal val mutableFunctions = mutableMapOf<FunctionDescriptor, JsirFunction>()
    internal val mutableProperties = mutableMapOf<VariableDescriptorWithAccessors, JsirProperty>()
}

class JsirFunction(val descriptor: FunctionDescriptor, val container: JsirContainer, val static: Boolean) {
    val parameters = mutableListOf<JsirParameter>()
    val body = mutableListOf<JsirStatement>()

    val variables: Set<JsirVariable>
        get() = variableContainer.variables

    init {
        container.mutableFunctions[descriptor] = this
    }

    fun delete() {
        if (container.mutableFunctions[descriptor] == this) {
            container.mutableFunctions.keys -= descriptor
        }
    }

    val variableContainer = JsirVariableContainer.Function(this)
}

class JsirParameter(val variable: JsirVariable) {
    val defaultBody = mutableListOf<JsirStatement>()
}

class JsirProperty(val descriptor: VariableDescriptorWithAccessors, val container: JsirContainer) {
    init {
        container.mutableProperties[descriptor] = this
    }

    fun delete() {
        if (container.mutableProperties[descriptor] == this) {
            container.mutableProperties.keys -= descriptor
        }
    }
}

class JsirClass(val descriptor: ClassDescriptor, val file: JsirFile, val outer: JsirClass?) : JsirContainer() {
    val closureFields = mutableSetOf<JsirVariable>()
    val delegateFields = mutableSetOf<JsirField.Delegate>()

    val innerClasses: Set<JsirClass>
        get() = mutableInnerClasses

    init {
        file.mutableClasses[descriptor] = this
        file.module.mutableClasses[descriptor] = this
        if (outer != null) {
            outer.mutableInnerClasses += this
        }
    }

    fun delete() {
        if (file.mutableClasses[descriptor] == this) {
            file.mutableClasses.keys -= descriptor
            file.module.mutableClasses.keys -= descriptor
        }
        if (outer != null) {
            outer.mutableInnerClasses -= this
        }
    }

    private val mutableInnerClasses = mutableSetOf<JsirClass>()
}

class JsirModule(val descriptor: ModuleDescriptor) {
    val files: List<JsirFile>
        get() = mutableFiles

    val classes: Map<ClassDescriptor, JsirClass>
        get() = mutableClasses

    internal val mutableFiles = mutableListOf<JsirFile>()

    internal val mutableClasses = mutableMapOf<ClassDescriptor, JsirClass>()

    val topLevelFunctions: Sequence<JsirFunction>
        get() = files.asSequence().flatMap { it.functions.values.asSequence() }

    val topLevelProperties: Sequence<JsirProperty>
        get() = files.asSequence().flatMap { it.properties.values.asSequence() }
}

class JsirFile(val module: JsirModule, val name: String) : JsirContainer() {
    val classes: Map<ClassDescriptor, JsirClass>
        get() = mutableClasses

    val initializerBody = mutableListOf<JsirStatement>()

    val variables: Set<JsirVariable>
        get() = variableContainer.variables

    val variableContainer = JsirVariableContainer.Initializer(this)

    init {
        module.mutableFiles += this
    }

    internal val mutableClasses = mutableMapOf<ClassDescriptor, JsirClass>()
}

sealed class JsirVariableContainer {
    internal val mutableVariables = mutableSetOf<JsirVariable>()

    val variables: Set<JsirVariable>
        get() = mutableVariables

    class Function internal constructor(val reference: JsirFunction) : JsirVariableContainer() {

        override fun equals(other: Any?) = other is Function && other.reference == reference

        override fun hashCode() = reference.hashCode()
    }

    class Initializer internal constructor(val reference: JsirFile) : JsirVariableContainer() {
        override fun equals(other: Any?) = other is Initializer && other.reference == reference

        override fun hashCode() = reference.hashCode()
    }

    fun createVariable(mutable: Boolean, suggestedName: String? = null) = JsirVariable(this, mutable, suggestedName)
}

class JsirVariable internal constructor(val container: JsirVariableContainer, var mutable: Boolean, var suggestedName: String? = null) {
    init {
        container.mutableVariables += this
    }

    fun delete() {
        container.mutableVariables -= this
    }

    val deleted: Boolean
        get() = this in container.mutableVariables
}

fun JsirVariable.makeReference() = JsirExpression.VariableReference(this)