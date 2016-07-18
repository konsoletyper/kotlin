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

package org.jetbrains.kotlin.js.ir.transform

import org.jetbrains.kotlin.js.ir.JsirContainer
import org.jetbrains.kotlin.js.ir.JsirPool
import org.jetbrains.kotlin.js.ir.transform.intrinsics.IntrinsicsTransformation
import org.jetbrains.kotlin.js.ir.transform.optimize.OptimizingTransformation

class Transformer {
    val transformations = listOf(IntrinsicsTransformation(), OptimizingTransformation())

    fun transform(pool: JsirPool) {
        ClassClosureTransformation().apply(pool)
        transformContainer(pool)
        for (cls in pool.classes.values) {
            transformContainer(cls)
        }
    }

    private fun transformContainer(container: JsirContainer) {
        transformations.forEach { it.apply(emptyList(), container.initializerBody) }
        for (function in container.functions.values) {
            val functionParameters = function.parameters.map { it.variable }
            for (parameter in function.parameters) {
                transformations.forEach { it.apply(functionParameters, parameter.defaultBody) }
            }
            transformations.forEach { it.apply(functionParameters, function.body) }
        }
    }
}
