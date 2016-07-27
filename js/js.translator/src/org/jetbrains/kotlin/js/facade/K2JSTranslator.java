/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.js.facade;

import com.google.dart.compiler.backend.js.ast.JsObjectScope;
import com.google.dart.compiler.backend.js.ast.JsProgram;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.descriptors.ModuleDescriptor;
import org.jetbrains.kotlin.js.analyze.TopDownAnalyzerFacadeForJS;
import org.jetbrains.kotlin.js.analyzer.JsAnalysisResult;
import org.jetbrains.kotlin.js.config.JsConfig;
import org.jetbrains.kotlin.js.facade.exceptions.TranslationException;
import org.jetbrains.kotlin.js.inline.JsInliner;
import org.jetbrains.kotlin.js.ir.JsirClass;
import org.jetbrains.kotlin.js.ir.JsirFunction;
import org.jetbrains.kotlin.js.ir.JsirModule;
import org.jetbrains.kotlin.js.ir.generate.JsirGenerator;
import org.jetbrains.kotlin.js.ir.render.*;
import org.jetbrains.kotlin.js.ir.render.builtins.*;
import org.jetbrains.kotlin.js.ir.render.nativecall.*;
import org.jetbrains.kotlin.js.ir.transform.Transformer;
import org.jetbrains.kotlin.js.translate.context.StandardClasses;
import org.jetbrains.kotlin.js.translate.context.TranslationContext;
import org.jetbrains.kotlin.js.translate.general.Translation;
import org.jetbrains.kotlin.progress.ProgressIndicatorAndCompilationCanceledStatus;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.resolve.BindingTrace;
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.jetbrains.kotlin.diagnostics.DiagnosticUtils.hasError;
import static org.jetbrains.kotlin.js.translate.utils.ExpandIsCallsKt.expandIsCalls;

/**
 * An entry point of translator.
 */
public final class K2JSTranslator {

    public static final String FLUSH_SYSTEM_OUT = "kotlin.out.flush();\n";
    public static final String GET_SYSTEM_OUT = "kotlin.out.buffer;\n";

    @NotNull
    private final JsConfig config;

    public K2JSTranslator(@NotNull JsConfig config) {
        this.config = config;
    }

    @NotNull
    public TranslationResult translate(
            @NotNull List<KtFile> files,
            @NotNull MainCallParameters mainCallParameters
    ) throws TranslationException {
        return translate(files, mainCallParameters, null);
    }

    @NotNull
    public TranslationResult translate(
            @NotNull List<KtFile> files,
            @NotNull MainCallParameters mainCallParameters,
            @Nullable JsAnalysisResult analysisResult
    ) throws TranslationException {
        if (analysisResult == null) {
            analysisResult = TopDownAnalyzerFacadeForJS.analyzeFiles(files, config);
            ProgressIndicatorAndCompilationCanceledStatus.checkCanceled();
        }

        BindingTrace bindingTrace = analysisResult.getBindingTrace();
        TopDownAnalyzerFacadeForJS.checkForErrors(JsConfig.withJsLibAdded(files, config), bindingTrace.getBindingContext());
        ModuleDescriptor moduleDescriptor = analysisResult.getModuleDescriptor();
        Diagnostics diagnostics = bindingTrace.getBindingContext().getDiagnostics();

        TranslationContext context = Translation.generateAst(bindingTrace, files, mainCallParameters, moduleDescriptor, config);
        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled();
        if (hasError(diagnostics)) return new TranslationResult.Fail(diagnostics);

        JsirGenerator irGenerator = new JsirGenerator(bindingTrace, moduleDescriptor);
        for (KtFile file : files) {
            file.accept(irGenerator, irGenerator.getContext());
        }
        JsirModule pool = irGenerator.getContext().getModule();
        Transformer transformer = new Transformer();
        transformer.transform(pool);

        JsirRenderer renderer = new JsirRenderer(new DefaultRenderingConfig());
        JsProgram altProgram = renderer.render(irGenerator.getContext().getModule());
        /*altProgram.getGlobalBlock().accept(new RecursiveJsVisitor() {
            @Override
            public void visitFunction(@NotNull JsFunction x) {
                super.visitFunction(x);
                FunctionPostProcessor optimizer = new FunctionPostProcessor(x);
                optimizer.apply();
            }
        });*/

        JsProgram program = JsInliner.process(context);
        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled();
        if (hasError(diagnostics)) return new TranslationResult.Fail(diagnostics);

        expandIsCalls(program, context);
        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled();

        List<String> importedModules = new ArrayList<String>(context.getImportedModules().keySet());
        return new TranslationResult.Success(config, files, altProgram, diagnostics, importedModules, moduleDescriptor);
    }

    private static class DefaultRenderingConfig implements JsirRenderingConfig {
        private final BuiltinNameContributor builtinNameContributor = new BuiltinNameContributor(
                StandardClasses.bindImplementations(new JsObjectScope(new JsProgram("").getRootScope(), "", null)));

        @NotNull
        @Override
        public List<InvocationRenderer> getInvocationRenderers() {
            return Arrays.asList(
                    new EqualsRenderer(), new ToStringRenderer(), new RangeMethodRenderer(), new NativeInvocationRenderer(),
                    new ArrayInvocationRenderer(), new StringLengthRenderer()
            );
        }

        @NotNull
        @Override
        public List<InstantiationRenderer> getInstantiationRenderers() {
            return Arrays.<InstantiationRenderer>asList(new NativeInvocationRenderer(), new ArrayInvocationRenderer());
        }

        @NotNull
        @Override
        public List<ObjectReferenceRenderer> getObjectReferenceRenderers() {
            return Collections.<ObjectReferenceRenderer>singletonList(new NativeObjectReferenceRenderer());
        }

        @NotNull
        @Override
        public List<Function1<JsirClass, Boolean>> getClassFilters() {
            return Collections.<Function1<JsirClass, Boolean>>singletonList(new NativeClassFilter());
        }

        @NotNull
        @Override
        public List<Function1<JsirFunction, Boolean>> getFunctionFilters() {
            return Collections.<Function1<JsirFunction, Boolean>>singletonList(new NativeFunctionFilter());
        }

        @NotNull
        @Override
        public List<ExternalNameContributor> getExternalNameContributors() {
            return Arrays.asList(builtinNameContributor, new NativeNameContributor());
        }
    }
}
