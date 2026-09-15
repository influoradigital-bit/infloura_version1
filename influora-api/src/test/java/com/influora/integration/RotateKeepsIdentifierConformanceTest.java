package com.influora.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;

/**
 * Gate for T-ROTATE-0913 (F-0519, F-0520, F-0766, F-0816): a store/connect method looks up an
 * existing row by workspace, and the EXISTING-row branch rotates the secret but never rewrites the
 * IDENTIFIER that says which external system the row points at, while the NEW-row branch sets it
 * correctly from the same parameter. Shipped four times independently (Shopify, WooCommerce twice,
 * Meta) because every previous fix was scoped to one integration. This gate is written against the
 * CLASS of defect, not the four instances, per the mechanical rule:
 *
 * <blockquote>in any method with an existing-row / new-row branch, every field the NEW-row branch
 * sets from a method PARAMETER must also be written on the EXISTING-row branch.</blockquote>
 *
 * <p><b>Real bytecode, not prose.</b> Same technique as {@code EntitlementConformanceTest} / {@code
 * BrandFeePublishPathConformanceTest}: {@code org.springframework.asm} reads the actual compiled
 * {@code .class} files under {@code target/classes/com/influora}. A javadoc paragraph or comment
 * naming {@code shopDomain} produces no bytecode and is invisible to this scan (see {@code
 * reference_grep_gate_matches_its_own_comment} — this repo has shipped exactly that hole before).
 *
 * <p><b>How the scan works, per method M declared on any scanned class:</b>
 *
 * <ul>
 *   <li>{@code BUILDER_PARAM_SET}: a call to a nested {@code *$Builder} fluent setter whose sole
 *       argument is a direct load of one of M's OWN parameter slots (not a local computed inside
 *       M, e.g. {@code encrypt(accessToken)}'s result) — this is "the new-row branch sets field F
 *       straight from parameter P".
 *   <li>{@code ROTATE_CALL}: a call to any method named {@code rotate<Something>} under {@code
 *       com.influora}, together with the parameter slots of M passed as its arguments — this is
 *       "the existing-row branch rotates the row using these parameters".
 *   <li>{@code LOOKUP_ARGS}: arguments to any {@code findBy...} repository call in M — these are
 *       the keys the existing row was already found BY (e.g. {@code workspaceId}), so a builder
 *       setter fed by one of these needs no separate rewrite: the row already matches on it by
 *       construction of the lookup that found it. Excluding these is what keeps {@code
 *       ConversionWebhookSecretService#generate} (which has no external identifier at all — {@code
 *       workspaceId} is the only parameter, and it IS the lookup key) out of this gate, matching
 *       the confirmed non-instance.
 * </ul>
 *
 * <p>For every method M that contains at least one {@code ROTATE_CALL} (i.e. M really has an
 * existing-row branch), every {@code BUILDER_PARAM_SET} slot in M that is not a {@code
 * LOOKUP_ARGS} key must appear among the arguments of at least one {@code ROTATE_CALL} in M. A
 * slot that appears in a builder setter but never reaches any rotate call is exactly the F-0519
 * class of defect: the new-row branch has proof of writing it, the existing-row branch has none.
 *
 * <p>This is a whole-method, not a real control-flow-aware, comparison: it does not distinguish
 * which branch a given instruction sits in beyond that the two calls of interest necessarily sit
 * in the two different arms of the same {@code if (existing.isPresent())} shape every rotate/store
 * method in this codebase uses (verified by reading all four instances before writing this gate).
 * A method with a coincidental unrelated rotate*-named call anywhere in its body would produce a
 * false negative (never observed in this codebase); this scan fails closed the other way — an
 * unrelated builder setter fed a parameter with no rotate call anywhere in the method reads as a
 * violation, which is exactly the "fifth integration adds a rotate method that drops an identifier"
 * case R5 exercises, not noise, because that shape (builder-setter-from-parameter existing
 * alongside an existing-row rotate call in the very same method) IS this codebase's established
 * pattern for exactly one thing: a store/connect method with a stale-identifier hazard.
 *
 * <p><b>NOT CHECKED</b> — disclosed, not implied:
 *
 * <ul>
 *   <li>This does not prove the identifier value passed to a rotate call at RUNTIME is actually the
 *       new one rather than some other in-scope variable of the same declared type happening to sit
 *       in a slot that isn't excluded — slot identity is a strong proxy (a parameter's slot is never
 *       reassigned in any of these methods) but not a full data-flow/points-to proof. {@link
 *       ShopifyTokenStorageTest#testStoreTokenRotatesToDifferentShopDomain()} and its Woo/Meta
 *       siblings are the behavioural proof this static check is paired with.
 *   <li>A rotate-shaped method that takes the identifier parameter and assigns it to a
 *       DIFFERENTLY-named field (not the field the entity's getter/column javadoc calls the
 *       identifier) would satisfy {@link #everyRotateMethodWritesEveryParameterToAField()} without
 *       fixing the real defect — this gate proves the parameter reaches SOME field, not that it is
 *       the semantically correct one. Reviewed by hand for all four known methods.
 *   <li>Bytecode freshness: unlike {@code BrandFeePublishPathConformanceTest}, this scan does not
 *       compare every scanned class file's mtime against its source (hundreds of classes under
 *       {@code com.influora} would make that check itself the slow/fragile part). Run {@code mvn -o
 *       test-compile} (or a full {@code mvn test}) before trusting a GREEN result, same discipline
 *       {@code reference_run_one_test_when_tree_broken} already documents for this repo.
 *   <li>{@code ConversionWebhookSecretService#generate} is confirmed NOT an instance (no builder
 *       setter survives the {@code LOOKUP_ARGS} exclusion), but this gate does not separately assert
 *       that non-membership — a regression there would simply mean the class stays absent from
 *       {@link #violations}, which is indistinguishable from "no such class exists yet". If a future
 *       change gives it a real external identifier, this gate does pick it up (verified by the R5
 *       falsification, which adds exactly that shape).
 * </ul>
 */
class RotateKeepsIdentifierConformanceTest {

    private record MethodKey(String className, String methodName, String descriptor) {
        @Override
        public String toString() {
            return className + "#" + methodName + descriptor;
        }
    }

    /** A builder-fluent-setter call fed directly by one of the enclosing method's own parameters. */
    private record BuilderParamSet(MethodKey inMethod, String setterName, int paramSlot) {}

    /** A call to some rotate*-named method, and which of the enclosing method's parameter slots it forwards. */
    private record RotateCall(MethodKey inMethod, String calledOwner, String calledName, Set<Integer> argSlots) {}

    private static List<BuilderParamSet> builderParamSets;
    private static List<RotateCall> rotateCalls;
    private static Map<MethodKey, Set<Integer>> lookupArgsByMethod;
    private static Set<MethodKey> methodsWithRotateCall;

    /** Every rotate*-named method found, and which of its OWN parameter slots got written to a field. */
    private static Map<MethodKey, Set<Integer>> fieldSetFromOwnParamByRotateMethod;
    private static Map<MethodKey, Integer> paramCountByRotateMethod;

    @BeforeAll
    static void scanCompiledClasses() throws Exception {
        builderParamSets = new ArrayList<>();
        rotateCalls = new ArrayList<>();
        lookupArgsByMethod = new HashMap<>();
        methodsWithRotateCall = new HashSet<>();
        fieldSetFromOwnParamByRotateMethod = new HashMap<>();
        paramCountByRotateMethod = new HashMap<>();

        for (Path classFile : allInfluoraClassFiles()) {
            scanBytecode(Files.readAllBytes(classFile));
        }

        for (RotateCall call : rotateCalls) {
            methodsWithRotateCall.add(call.inMethod());
        }
    }

    private static void scanBytecode(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        String selfInternalName = reader.getClassName();
        String selfSimpleName = simpleName(selfInternalName);

        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                        Set<Integer> paramSlots = parameterSlots(descriptor, isStatic);
                        MethodKey methodKey = new MethodKey(selfSimpleName, name, descriptor);
                        boolean rotateShaped = isRotateMethodName(name);
                        if (rotateShaped) {
                            paramCountByRotateMethod.put(methodKey, paramSlots.size());
                            fieldSetFromOwnParamByRotateMethod.computeIfAbsent(methodKey, k -> new HashSet<>());
                        }

                        return new MethodVisitor(Opcodes.ASM9) {
                            /** Slots loaded since the last non-load instruction, in bytecode order. */
                            private final List<Integer> pending = new ArrayList<>();

                            @Override
                            public void visitVarInsn(int opcode, int var) {
                                boolean isLoad =
                                        opcode == Opcodes.ALOAD
                                                || opcode == Opcodes.ILOAD
                                                || opcode == Opcodes.LLOAD
                                                || opcode == Opcodes.FLOAD
                                                || opcode == Opcodes.DLOAD;
                                if (isLoad) {
                                    pending.add(var);
                                } else {
                                    pending.clear();
                                }
                            }

                            @Override
                            public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
                                // (d) CHECK A: this.field = <own parameter> is `aload_0, aload_N, PUTFIELD`.
                                if (opcode == Opcodes.PUTFIELD
                                        && rotateShaped
                                        && owner.equals(selfInternalName)
                                        && pending.size() == 2
                                        && pending.get(0) == 0
                                        && paramSlots.contains(pending.get(1))) {
                                    fieldSetFromOwnParamByRotateMethod
                                            .computeIfAbsent(methodKey, k -> new HashSet<>())
                                            .add(pending.get(1));
                                }
                                pending.clear();
                            }

                            @Override
                            public void visitMethodInsn(
                                    int opcode, String owner, String calledName, String calledDesc, boolean isInterface) {
                                List<Integer> argsSnapshot = List.copyOf(pending);
                                pending.clear();

                                if (owner.equals(selfInternalName) && calledName.equals("<init>")) {
                                    return; // constructor calls are not evidence of anything this gate checks
                                }

                                if (calledName.startsWith("findBy") && owner.startsWith("com/influora/")) {
                                    lookupArgsByMethod
                                            .computeIfAbsent(methodKey, k -> new HashSet<>())
                                            .addAll(retainOnly(argsSnapshot, paramSlots));
                                    return;
                                }

                                if (isRotateMethodName(calledName) && owner.startsWith("com/influora/")) {
                                    rotateCalls.add(
                                            new RotateCall(
                                                    methodKey,
                                                    owner,
                                                    calledName,
                                                    new HashSet<>(retainOnly(argsSnapshot, paramSlots))));
                                    return;
                                }

                                if (owner.endsWith("$Builder") && argsSnapshot.size() == 1) {
                                    int slot = argsSnapshot.get(0);
                                    if (paramSlots.contains(slot)) {
                                        builderParamSets.add(new BuilderParamSet(methodKey, calledName, slot));
                                    }
                                }
                            }
                        };
                    }
                },
                ClassReader.SKIP_FRAMES);
    }

    private static List<Integer> retainOnly(List<Integer> slots, Set<Integer> allowed) {
        List<Integer> out = new ArrayList<>();
        for (Integer s : slots) {
            if (allowed.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean isRotateMethodName(String name) {
        return name.length() > 6 && name.startsWith("rotate") && Character.isUpperCase(name.charAt(6));
    }

    /** JVM local variable slots occupied by M's own formal parameters (never {@code this}). */
    private static Set<Integer> parameterSlots(String descriptor, boolean isStatic) {
        Set<Integer> slots = new HashSet<>();
        int slot = isStatic ? 0 : 1;
        for (Type argType : Type.getArgumentTypes(descriptor)) {
            slots.add(slot);
            slot += argType.getSize();
        }
        return slots;
    }

    private static String simpleName(String internalName) {
        int idx = internalName.lastIndexOf('/');
        return idx < 0 ? internalName : internalName.substring(idx + 1);
    }

    // ===================== the class-level gate =====================

    @Test
    @DisplayName(
            "F-0519/F-0520/F-0766/F-0816: every field the new-row branch sets from a parameter is"
                    + " also written on the existing-row branch's rotate call")
    void everyNewRowParameterReachesTheExistingRowRotateCall() {
        List<String> violations = new ArrayList<>();

        for (BuilderParamSet set : builderParamSets) {
            MethodKey method = set.inMethod();
            if (!methodsWithRotateCall.contains(method)) {
                // No existing-row rotate call anywhere in this method at all -- this is a
                // pure-insert method (or a method this gate's heuristics don't apply to), not the
                // existing-row/new-row shape the class of defect requires. Nothing to compare
                // against, so nothing to flag.
                continue;
            }
            Set<Integer> lookupKeys = lookupArgsByMethod.getOrDefault(method, Set.of());
            if (lookupKeys.contains(set.paramSlot())) {
                // This parameter IS the key the existing row was found by (e.g. workspaceId) --
                // it cannot have changed between the lookup and the rotate call, so there is
                // nothing to rewrite. Excluding it is what keeps ConversionWebhookSecretService's
                // sole parameter (workspaceId, also its lookup key) from being a false positive.
                continue;
            }
            boolean reachesSomeRotateCall =
                    rotateCalls.stream()
                            .anyMatch(rc -> rc.inMethod().equals(method) && rc.argSlots().contains(set.paramSlot()));
            if (!reachesSomeRotateCall) {
                violations.add(
                        method
                                + ": builder setter '"
                                + set.setterName()
                                + "' is fed directly from parameter slot "
                                + set.paramSlot()
                                + " in what this method's shape marks as the new-row branch, but no"
                                + " rotate*(...) call in this same method forwards that parameter --"
                                + " the existing-row branch never rewrites it (stale-identifier-on-rotate"
                                + " class, F-0519/F-0520/F-0766/F-0816)");
            }
        }

        assertThat(violations)
                .as(
                        "%d method(s) set a field from a new-row parameter without also passing that"
                            + " parameter to the existing-row branch's rotate call:%n%s",
                        violations.size(),
                        String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("every rotate*(...) method writes every one of its own parameters to a field")
    void everyRotateMethodWritesEveryParameterToAField() {
        List<String> violations = new ArrayList<>();

        for (Map.Entry<MethodKey, Integer> entry : paramCountByRotateMethod.entrySet()) {
            MethodKey method = entry.getKey();
            int declaredParamCount = entry.getValue();
            Set<Integer> written = fieldSetFromOwnParamByRotateMethod.getOrDefault(method, Set.of());
            // Slots are assigned starting at 1 (instance methods) and are contiguous only for
            // single-slot types (String/Instant/etc. -- every parameter on every rotate method in
            // this codebase today). written.size() vs declaredParamCount is therefore a valid
            // proxy for "every parameter", not just "some parameter": a rotate method that ignores
            // even one parameter cannot reach declaredParamCount distinct written slots.
            if (written.size() < declaredParamCount) {
                violations.add(
                        method
                                + ": declares "
                                + declaredParamCount
                                + " parameter(s) but only "
                                + written.size()
                                + " reach a `this.field = param` assignment -- a rotate method that"
                                + " accepts an identifier and never assigns it is the same class of bug"
                                + " with the compile-time guard defeated from the inside");
            }
        }

        assertThat(violations)
                .as(
                        "%d rotate*(...) method(s) accept a parameter they never write to a field:%n%s",
                        violations.size(),
                        String.join("\n", violations))
                .isEmpty();
    }

    // ===================== compiled-classes discovery (mirrors EntitlementConformanceTest) =====================

    private static Path classesRoot() throws URISyntaxException {
        var location = RotateKeepsIdentifierConformanceTest.class.getProtectionDomain().getCodeSource().getLocation();
        // This test class itself lives under target/test-classes; the production classes this
        // gate must scan are the sibling target/classes directory.
        Path testClasses = new File(location.toURI()).toPath();
        return testClasses.getParent().resolve("classes");
    }

    private static List<Path> allInfluoraClassFiles() throws IOException, URISyntaxException {
        Path root = classesRoot().resolve("com").resolve("influora");
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> found = walk.filter(p -> p.toString().endsWith(".class")).toList();
            assertThat(found).as("sanity: must find compiled com.influora classes at all").isNotEmpty();
            return found;
        }
    }
}
