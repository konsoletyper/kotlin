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
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.js.ir.*

class ClassClosureTransformation {
    fun apply(pool: JsirModule) {
        val closureFields = mutableMapOf<ClassDescriptor, Set<JsirVariable>>()
        for (cls in pool.classes.values) {
            val descriptor = cls.descriptor
            if (descriptor.containingDeclaration !is FunctionDescriptor) continue
            val transformation = SingleClassClosureTransformation(pool, cls)
            transformation.apply(cls)
            transformation.addClosureFields()
            closureFields[descriptor] = transformation.closureFields
        }
        applyToCallSites(closureFields, pool)
    }

    fun applyToCallSites(closureFields: Map<ClassDescriptor, Set<JsirVariable>>, pool: JsirModule) {
        for (function in (pool.functions.values + pool.classes.values.flatMap { it.functions.values })) {
            applyToCallSites(closureFields, function.parameters.flatMap { it.defaultBody })
            applyToCallSites(closureFields, function.body)
        }
        for (cls in pool.classes.values) {
            applyToCallSites(closureFields, cls.initializerBody)
        }
        applyToCallSites(closureFields, pool.initializerBody)
    }

    fun applyToCallSites(closureFields: Map<ClassDescriptor, Set<JsirVariable>>, statements: List<JsirStatement>) {
        statements.visit(object : JsirVisitor<Unit, Unit> {
            override fun accept(statement: JsirStatement, inner: () -> Unit) = inner()

            override fun accept(expression: JsirExpression, inner: () -> Unit) {
                when (expression) {
                    is JsirExpression.Invocation -> applyToCallSite(closureFields, expression.function, expression.arguments)
                    is JsirExpression.NewInstance -> applyToCallSite(closureFields, expression.constructor, expression.arguments)
                }
                inner()
            }
        })
    }

    private fun applyToCallSite(
            closureFields: Map<ClassDescriptor, Set<JsirVariable>>,
            function: FunctionDescriptor, arguments: MutableList<JsirExpression>
    ) {
        if (function !is ConstructorDescriptor) return

        val fields = closureFields[function.containingDeclaration] ?: return
        arguments.addAll(0, fields.map { JsirExpression.VariableReference(it, false) })
    }
}

private class SingleClassClosureTransformation(val pool: JsirModule, val root: JsirClass) : JsirMapper {
    private var currentClass: JsirClass = root
    private var currentFunction: JsirFunction? = null
    val closureFields = mutableSetOf<JsirVariable>()

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

    fun addClosureFields() {
        root.closureFields += closureFields
        for (constructorDescriptor in root.descriptor.constructors) {
            val constructor = root.functions[constructorDescriptor] ?: continue
            val statements = mutableListOf<JsirStatement>()
            val parameters = mutableListOf<JsirParameter>()
            for (closureField in closureFields) {
                val parameter = JsirVariable(closureField.suggestedName)
                val reference = JsirExpression.FieldAccess(JsirExpression.This, JsirField.Closure(closureField))
                statements += JsirStatement.Assignment(reference, parameter.makeReference())
                parameters += JsirParameter(parameter)
            }

            constructor.parameters.addAll(0, parameters)
            constructor.body.addAll(0, statements)
        }
    }

    private fun process(cls: JsirClass, action: () -> Unit) {
        val descriptor = cls.descriptor
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
            closureFields += expression.variable
            JsirExpression.FieldAccess(getReceiver(), JsirField.Closure(expression.variable))
        }
        else {
            expression
        }
    }

    private fun getReceiver(): JsirExpression {
        var receiver: JsirExpression = JsirExpression.This
        var cls = currentClass.descriptor
        while (cls != root.descriptor) {
            receiver = JsirExpression.FieldAccess(receiver, JsirField.OuterClass(cls))
            cls = cls.containingDeclaration as ClassDescriptor
        }
        return receiver
    }
}