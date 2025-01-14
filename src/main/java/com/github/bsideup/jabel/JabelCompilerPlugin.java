package com.github.bsideup.jabel;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Source;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JavacMessages;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.implementation.bytecode.Removal;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.constant.IntegerConstant;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

import java.util.*;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class JabelCompilerPlugin implements Plugin {

    static {
        Map<String, AsmVisitorWrapper> visitors = new HashMap<String, AsmVisitorWrapper>() {{
            // Disable the preview feature check
            AsmVisitorWrapper checkSourceLevelAdvice = Advice.to(CheckSourceLevelAdvice.class)
                    .on(named("checkSourceLevel").and(takesArguments(2)));

            // Allow features that were introduced together with Records (local enums, static inner members, ...)
            AsmVisitorWrapper allowRecordsEraFeaturesAdvice = MemberSubstitution.relaxed()
                    .field(named("allowRecords"))
                    .onRead()
                    .replaceWith((instrumentedType, instrumentedMethod, typePool) ->
                            (targetType, target, parameters, result, freeOffset) ->
                                    new StackManipulation.Compound(Removal.of(targetType), IntegerConstant.forValue(true)))
                                    // remove aload_0
                    .on(any());

            put("com.sun.tools.javac.parser.JavacParser", new AsmVisitorWrapper.Compound(
                    checkSourceLevelAdvice, allowRecordsEraFeaturesAdvice));

            put("com.sun.tools.javac.parser.JavaTokenizer", checkSourceLevelAdvice);

            put("com.sun.tools.javac.comp.Check", allowRecordsEraFeaturesAdvice);
            put("com.sun.tools.javac.comp.Attr", allowRecordsEraFeaturesAdvice);
            put("com.sun.tools.javac.comp.Resolve", allowRecordsEraFeaturesAdvice);

            // Lower the source requirement for supported features
            put("com.sun.tools.javac.code.Source$Feature", Advice.to(AllowedInSourceAdvice.class)
                    .on(named("allowedInSource").and(takesArguments(1))));
        }};

        try {
            ByteBuddyAgent.install();
        } catch (Exception e) {
            ByteBuddyAgent.install(new ByteBuddyAgent.AttachmentProvider.Compound(
                    ByteBuddyAgent.AttachmentProvider.ForJ9Vm.INSTANCE,
                    ByteBuddyAgent.AttachmentProvider.ForStandardToolsJarVm.JVM_ROOT,
                    ByteBuddyAgent.AttachmentProvider.ForStandardToolsJarVm.JDK_ROOT,
                    ByteBuddyAgent.AttachmentProvider.ForStandardToolsJarVm.MACINTOSH,
                    ByteBuddyAgent.AttachmentProvider.ForUserDefinedToolsJar.INSTANCE,
                    ByteBuddyAgent.AttachmentProvider.ForEmulatedAttachment.INSTANCE
            ));
        }

        ByteBuddy byteBuddy = new ByteBuddy();

        ClassLoader classLoader = JavacTask.class.getClassLoader();
        ClassFileLocator classFileLocator = ClassFileLocator.ForClassLoader.of(classLoader);
        TypePool typePool = TypePool.ClassLoading.of(classLoader);

        visitors.forEach((className, visitor) -> byteBuddy.redefine(typePool.describe(className).resolve(), classFileLocator)
                .visit(visitor)
                .make()
                .load(classLoader, ClassReloadingStrategy.fromInstalledAgent()));

        JavaModule jabelModule = JavaModule.ofType(JabelCompilerPlugin.class);
        ClassInjector.UsingInstrumentation.redefineModule(
                ByteBuddyAgent.getInstrumentation(),
                JavaModule.ofType(JavacTask.class),
                Collections.emptySet(),
                Collections.emptyMap(),
                new HashMap<String, Set<JavaModule>>() {{
                    put("com.sun.tools.javac.api", Collections.singleton(jabelModule));
                    put("com.sun.tools.javac.tree", Collections.singleton(jabelModule));
                    put("com.sun.tools.javac.code", Collections.singleton(jabelModule));
                    put("com.sun.tools.javac.util", Collections.singleton(jabelModule));
                }},
                Collections.emptySet(),
                Collections.emptyMap()
        );
    }

    @Override
    public void init(JavacTask task, String... args) {
        Context context = ((JavacTaskImpl) task).getContext();
        JavacMessages.instance(context).add(locale -> new DesugarResourceBundle());

        task.addTaskListener(new RecordsRetrofittingTaskListener(context));
    }

    @Override
    public String getName() {
        return "jabel";
    }

    // Make it auto start on Java 14+
    public boolean autoStart() {
        return true;
    }

    static class AllowedInSourceAdvice {

        @Advice.OnMethodEnter
        static void allowedInSource(@Advice.This Source.Feature feature,
                                    @Advice.Argument(value = 0, readOnly = false) Source source) {
            switch (feature.name()) {
                case "PRIVATE_SAFE_VARARGS":
                case "SWITCH_EXPRESSION":
                case "SWITCH_RULE":
                case "SWITCH_MULTIPLE_CASE_LABELS":
                case "LOCAL_VARIABLE_TYPE_INFERENCE":
                case "VAR_SYNTAX_IMPLICIT_LAMBDAS":
                case "DIAMOND_WITH_ANONYMOUS_CLASS_CREATION":
                case "EFFECTIVELY_FINAL_VARIABLES_IN_TRY_WITH_RESOURCES":
                case "TEXT_BLOCKS":
                case "PATTERN_MATCHING_IN_INSTANCEOF":
                case "REIFIABLE_TYPES_INSTANCEOF":
                case "RECORDS":
                    //noinspection UnusedAssignment
                    source = Source.DEFAULT;
                    break;
            }
        }
    }

    static class CheckSourceLevelAdvice {

        @Advice.OnMethodEnter
        static void checkSourceLevel(@Advice.Argument(value = 1, readOnly = false) Source.Feature feature) {
            if (feature.allowedInSource(Source.JDK8)) {
                //noinspection UnusedAssignment
                feature = Source.Feature.LAMBDA;
            }
        }
    }

    private static class DesugarResourceBundle extends ResourceBundle{
        @Override
        protected Object handleGetObject(String key) {
            return "{0}";
        }

        @Override
        public Enumeration<String> getKeys() {
            return Collections.enumeration(Collections.singletonList("missing.desugar.on.record"));
        }
    }
}
