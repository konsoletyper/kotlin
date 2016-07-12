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

import org.jetbrains.kotlin.js.ir.JsirFunction
import org.jetbrains.kotlin.js.ir.transform.Transformation

class OptimizingTransformation : Transformation {
    private val optimizations = listOf(CompareToElimination())

    override fun apply(function: JsirFunction) {
        val currentOptimizations = mutableSetOf<Optimization>()
        currentOptimizations += optimizations

        while (currentOptimizations.isNotEmpty()) {
            val optimization = currentOptimizations.first()
            currentOptimizations -= optimization
            val newOptimizations = optimization.apply(function)
            currentOptimizations -= newOptimizations
            currentOptimizations += newOptimizations
        }
    }
}
