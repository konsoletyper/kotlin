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

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptorWithAccessors

interface JsirContainer {
    val functions: MutableMap<FunctionDescriptor, JsirFunction>
    val properties: MutableMap<VariableDescriptorWithAccessors, JsirProperty>
    val initializerBody: MutableList<JsirStatement>
}

class JsirFunction(val declaration: FunctionDescriptor) {
    val parameters = mutableListOf<JsirParameter>()
    val body = mutableListOf<JsirStatement>()
}

class JsirParameter(val variable: JsirVariable) {
    val defaultBody = mutableListOf<JsirStatement>()
}

class JsirProperty(val declaration: VariableDescriptorWithAccessors)

class JsirClass(val declaration: ClassDescriptor) : JsirContainer {
    override val initializerBody = mutableListOf<JsirStatement>()

    override val functions = mutableMapOf<FunctionDescriptor, JsirFunction>()

    override val properties = mutableMapOf<VariableDescriptorWithAccessors, JsirProperty>()
}

class JsirPool(val module: ModuleDescriptor) : JsirContainer {
    override val functions = mutableMapOf<FunctionDescriptor, JsirFunction>()

    override val properties = mutableMapOf<VariableDescriptorWithAccessors, JsirProperty>()

    val classes = mutableMapOf<ClassDescriptor, JsirClass>()

    override val initializerBody = mutableListOf<JsirStatement>()
}