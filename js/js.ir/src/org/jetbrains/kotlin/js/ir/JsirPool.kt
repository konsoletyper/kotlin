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

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor

class JsirPool(val module: ModuleDescriptor) {
    private val functionMap = mutableMapOf<FunctionDescriptor, JsirFunction>()

    val functions: Map<FunctionDescriptor, JsirFunction> = functionMap

    private val propertySet = mutableSetOf<PropertyDescriptor>()

    val properties: Set<PropertyDescriptor> = propertySet

    fun addFunction(descriptor: FunctionDescriptor, function: JsirFunction) {
        functionMap[descriptor] = function
    }

    fun addProperty(property: PropertyDescriptor) {
        propertySet += property
    }
}
