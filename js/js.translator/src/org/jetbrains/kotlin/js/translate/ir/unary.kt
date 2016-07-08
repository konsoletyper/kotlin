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

package org.jetbrains.kotlin.js.translate.ir

import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.JsirVariable
import org.jetbrains.kotlin.js.ir.makeReference
import org.jetbrains.kotlin.js.translate.utils.BindingUtils
import org.jetbrains.kotlin.js.translate.utils.PsiUtils
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.psiUtil.getTextWithLocation
import org.jetbrains.kotlin.resolve.BindingContextUtils
import org.jetbrains.kotlin.resolve.calls.callUtil.getFunctionResolvedCallWithAssert
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.types.expressions.OperatorConventions

internal fun JsirContext.generateUnary(expression: KtUnaryExpression): JsirExpression {
    val operationToken = expression.operationReference.getReferencedNameElementType()
    if (operationToken == KtTokens.EXCLEXCL) {
        val baseExpression = PsiUtils.getBaseExpression(expression)
        val type = BindingContextUtils.getTypeNotNull(bindingContext, baseExpression)
        val generatedExpression = generate(baseExpression)
        return if (type.isMarkedNullable) requireNonNull(generatedExpression) else generatedExpression
    }

    if (operationToken == KtTokens.MINUS) {
        val baseExpression = PsiUtils.getBaseExpression(expression)
        if (baseExpression is KtConstantExpression) {
            val compileTimeValue = ConstantExpressionEvaluator.getConstant(expression, bindingContext) ?:
                                   error("Expression is not compile time value: ${expression.getTextWithLocation()}")
            val value = BindingUtils.getCompileTimeValue(bindingContext, expression, compileTimeValue)
            if (value is Long) {
                return JsirExpression.Constant(-value)
            }
        }
    }

    if (OperatorConventions.INCREMENT_OPERATIONS.contains(operationToken)) {
        return generateIncrement(expression)
    }

    val resolvedCall = expression.getFunctionResolvedCallWithAssert(bindingContext)
    return generateInvocation(expression.baseExpression!!, resolvedCall)
}

private fun JsirContext.generateIncrement(expression: KtUnaryExpression): JsirExpression {
    val resolvedCall = expression.getFunctionResolvedCallWithAssert(bindingContext)
    val variable = generateVariable(expression.baseExpression!!)

    val temporary = JsirVariable()
    assign(temporary.makeReference(), variable.get())
    variable.set(generateInvocation(expression.baseExpression!!, resolvedCall))
    return temporary.makeReference()
}