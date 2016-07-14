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

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.js.translate.utils.BindingUtils
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getTextWithLocation
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.types.KotlinType

internal fun JsirContext.generateWhen(psi: KtWhenExpression): JsirExpression {
    val subjectPsi = psi.subjectExpression
    val subject = subjectPsi?.let { memoize(it) }
    val result = JsirVariable().makeReference()

    var exit: (() -> Unit)? = null
    for (entryPsi in psi.entries) {
        if (entryPsi.isElse) {
            assign(result, generate(entryPsi.expression!!))
        }
        else {
            val condition = if (entryPsi.conditions.size == 1) {
                generateWhenCondition(subject, subjectPsi, entryPsi.conditions[0])
            }
            else {
                val temporary = JsirVariable().makeReference()
                assign(temporary, generateWhenCondition(subject, subjectPsi, entryPsi.conditions.first()))
                for (conditionPsi in entryPsi.conditions.drop(1)) {
                    val conditional = JsirStatement.If(temporary.negate())
                    withSource(conditionPsi) {
                        nestedBlock(conditional.thenBody) {
                            append(generateWhenCondition(subject, subjectPsi, conditionPsi))
                        }
                    }
                    append(conditional)
                }
                temporary
            }

            val conditional = JsirStatement.If(condition)
            nestedBlock(conditional.thenBody) {
                assign(result, generate(entryPsi.expression!!))
            }
            append(conditional)

            val newExit = enterBlock(conditional.elseBody)
            if (exit == null) {
                exit = newExit
            }
        }
    }

    exit?.let { it() }
    return result
}

private fun JsirContext.generateWhenCondition(subject: JsirExpression?, subjectPsi: KtExpression?, psi: KtWhenCondition) = when (psi) {
    is KtWhenConditionIsPattern -> {
        val type = BindingUtils.getTypeByReference(bindingContext, psi.typeReference!!)
        val result = generateInstanceOf(subject!!, BindingUtils.getTypeForExpression(bindingContext, subjectPsi!!), type)
        if (psi.isNegated) result.negate() else result
    }
    is KtWhenConditionWithExpression -> {
        if (subject != null) {
            val type = BindingUtils.getTypeForExpression(bindingContext, subjectPsi!!).getIRType()
            if (type == JsirType.ANY) {
                JsirExpression.Binary(JsirBinaryOperation.EQ, type, subject, generate(psi.expression!!))
            }
            else {
                JsirExpression.Binary(JsirBinaryOperation.EQUALS_METHOD, type, subject, generate(psi.expression!!))
            }
        }
        else {
            generate(psi.expression!!)
        }
    }
    is KtWhenConditionInRange -> {
        JsirExpression.True
    }
    else -> error("Unsupported when condition ${psi.getTextWithLocation()}")
}

private fun KotlinType.getIRType(): JsirType {
    val descriptor = constructor.declarationDescriptor
    if (descriptor !is ClassDescriptor) return JsirType.ANY
    val primitive = KotlinBuiltIns.getPrimitiveTypeByFqName(descriptor.fqNameUnsafe) ?: return JsirType.ANY

    return when (primitive) {
        PrimitiveType.BOOLEAN -> JsirType.BOOLEAN
        PrimitiveType.BYTE -> JsirType.BYTE
        PrimitiveType.SHORT -> JsirType.SHORT
        PrimitiveType.CHAR -> JsirType.CHAR
        PrimitiveType.INT -> JsirType.INT
        PrimitiveType.LONG -> JsirType.LONG
        PrimitiveType.FLOAT -> JsirType.FLOAT
        PrimitiveType.DOUBLE -> JsirType.DOUBLE
    }
}