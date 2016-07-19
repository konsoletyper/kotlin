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

import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.isDynamic
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf

internal fun JsirContext.generateInstanceOf(value: JsirExpression, sourceType: KotlinType?, targetType: KotlinType): JsirExpression {
    if (sourceType != null && !sourceType.isDynamic() && sourceType.isSubtypeOf(targetType)) return JsirExpression.Constant(true)

    return JsirExpression.InstanceOf(value, targetType)
}

internal fun JsirContext.generateCast(value: JsirExpression, sourceType: KotlinType?, targetType: KotlinType): JsirExpression {
    if (sourceType != null && !sourceType.isDynamic() && sourceType.isSubtypeOf(targetType)) return value

    return JsirExpression.Cast(value, targetType)
}