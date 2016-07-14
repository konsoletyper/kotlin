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

package org.jetbrains.kotlin.js.ir.render

import com.google.dart.compiler.backend.js.ast.*
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.ReflectionTypes
import org.jetbrains.kotlin.builtins.isFunctionTypeOrSubtype
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.js.descriptorUtils.nameIfStandardType
import org.jetbrains.kotlin.js.patterns.NamePredicate
import org.jetbrains.kotlin.js.patterns.typePredicates.CHAR_SEQUENCE
import org.jetbrains.kotlin.js.patterns.typePredicates.COMPARABLE
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils

internal fun JsirRenderingContext.renderInstanceOf(expression: JsExpression, type: KotlinType): JsExpression {
    var builtinCheck = getIsTypeCheckCallableForBuiltin(expression, type)
    if (builtinCheck != null) return builtinCheck

    builtinCheck = getIsTypeCheckCallableForPrimitiveBuiltin(expression, type)
    if (builtinCheck != null) return builtinCheck

    val typeParameterDescriptor = TypeUtils.getTypeParameterDescriptorOrNull(type)
    if (typeParameterDescriptor != null) {
        if (typeParameterDescriptor.isReified) {
            val classRef = getInternalName(typeParameterDescriptor.typeConstructor.declarationDescriptor as DeclarationDescriptor)
            return JsInvocation(kotlinReference("isType"), expression, classRef.makeRef())
        }
        error("Not supported yet")
    }

    val cls = DescriptorUtils.getClassDescriptorForType(type)
    return JsInvocation(kotlinReference("isType"), expression, getInternalName(cls).makeRef())
}

private fun JsirRenderingContext.getIsTypeCheckCallableForBuiltin(value: JsExpression, type: KotlinType): JsExpression? {
    if (KotlinBuiltIns.isAnyOrNullableAny(type)) return JsAstUtils.inequality(value, JsLiteral.NULL)

    if (type.isFunctionTypeOrSubtype && !ReflectionTypes.isNumberedKPropertyOrKMutablePropertyType(type)) {
        return JsAstUtils.typeOfIs(value, getStringLiteral("function"))
    }

    if (KotlinBuiltIns.isArray(type)) {
        return JsInvocation(JsAstUtils.pureFqn("isArray", JsAstUtils.pureFqn("Array", null)), value)
    }

    if (CHAR_SEQUENCE.apply(type)) return kotlinReference("isCharSequence")

    if (COMPARABLE.apply(type)) return kotlinReference("isComparable")

    return null
}

private fun JsirRenderingContext.getIsTypeCheckCallableForPrimitiveBuiltin(value: JsExpression, type: KotlinType): JsExpression? {
    val typeName = type.nameIfStandardType

    return when {
        NamePredicate.STRING.apply(typeName) -> JsAstUtils.typeOfIs(value, getStringLiteral("string"))
        NamePredicate.BOOLEAN.apply(typeName) -> JsAstUtils.typeOfIs(value, getStringLiteral("boolean"))
        NamePredicate.LONG.apply(typeName) -> {
            JsBinaryOperation(JsBinaryOperator.INSTANCEOF, value, kotlinReference("Long"))
        }
        NamePredicate.NUMBER.apply(typeName) -> JsAstUtils.typeOfIs(value, getStringLiteral("number"))
        NamePredicate.CHAR.apply(typeName) -> JsInvocation(kotlinReference("isChar"), value)
        NamePredicate.PRIMITIVE_NUMBERS_MAPPED_TO_PRIMITIVE_JS.apply(typeName) -> {
            JsAstUtils.typeOfIs(value, getStringLiteral("number"))
        }
        else -> return null
    }

}
