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

package org.jetbrains.kotlin.js.ir.transform.optimize

import org.jetbrains.kotlin.js.ir.JsirStatement
import org.jetbrains.kotlin.js.ir.JsirVariable
import org.jetbrains.kotlin.js.ir.JsirVariableContainer
import kotlin.reflect.KClass

interface Optimization {
    fun apply(variableContainer: JsirVariableContainer, body: MutableList<JsirStatement>): List<KClass<out Optimization>>
}
