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

package org.jetbrains.kotlin.js.ir.render.nativecall

import com.google.dart.compiler.backend.js.ast.JsExpression
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.js.ir.render.JsirRenderingContext
import org.jetbrains.kotlin.js.ir.render.ObjectReferenceRenderer
import org.jetbrains.kotlin.js.translate.utils.AnnotationsUtils

class NativeObjectReferenceRenderer : ObjectReferenceRenderer {
    override fun isApplicable(descriptor: ClassDescriptor): Boolean {
        return AnnotationsUtils.isNativeObject(descriptor) || AnnotationsUtils.isLibraryObject(descriptor)
    }

    override fun render(descriptor: ClassDescriptor, context: JsirRenderingContext): JsExpression {
        return context.getInternalName(descriptor).makeRef()
    }
}
