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

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.js.ir.*

class ClassClosureTransformation {
    fun apply(pool: JsirPool) {
        for (cls in pool.classes.values) {
            val descriptor = cls.declaration
            if (descriptor.containingDeclaration !is FunctionDescriptor) continue
            val transformation = SingleClassClosureTransformation(pool, cls)
            transformation.apply(cls)
        }
    }
}

private class SingleClassClosureTransformation(val pool: JsirPool, val root: JsirClass) : JsirMapper {
    private var currentClass: JsirClass = root
    private var currentFunction: JsirFunction? = null

    fun apply(cls: JsirClass) = process(cls) {
        currentClass = cls
        currentFunction = null
        cls.initializerBody.replace(this)
        for (function in cls.functions.values) {
            currentFunction = function
            function.parameters.forEach { it.defaultBody.replace(this) }
            function.body.replace(this)
        }
    }

    private fun process(cls: JsirClass, action: () -> Unit) {
        val descriptor = cls.declaration
        action()
        for (innerDescriptor in descriptor.unsubstitutedInnerClassesScope.getContributedDescriptors()) {
            if (innerDescriptor is ClassDescriptor && innerDescriptor.isInner) {
                apply(pool.classes[innerDescriptor]!!)
            }
        }
    }

    override fun map(statement: JsirStatement, canChangeType: Boolean): JsirStatement = statement

    override fun map(expression: JsirExpression): JsirExpression {
        return if (expression is JsirExpression.VariableReference && expression.free) {
            JsirExpression.FieldAccess(getReceiver(), JsirField.Closure(expression.variable))
        }
        else {
            expression
        }
    }

    private fun getReceiver(): JsirExpression {
        var receiver: JsirExpression = JsirExpression.This
        var cls = currentClass.declaration
        while (cls != root.declaration) {
            receiver = JsirExpression.FieldAccess(receiver, JsirField.OuterClass(cls))
            cls = cls.containingDeclaration as ClassDescriptor
        }
        return receiver
    }
}