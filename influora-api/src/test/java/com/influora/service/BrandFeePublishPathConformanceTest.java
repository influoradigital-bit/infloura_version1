package com.influora.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.security.AuthPrincipal;
import com.influora.service.meera.tool.ConfirmLaunchExecutor;
import com.influora.web.dto.campaign.CampaignDtos.CampaignPatchRequest;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;

/**
 * Closes the blind spot {@code EntitlementConformanceTest}'s class javadoc discloses under
 * "Disclosed blind spot — BRAND_FEE_BPS has two callers, this checks for one": that gate's RATE
 * evidence is "somebody outside {@code BrandCampaignFeeService} calls {@code chargeOnPublish}",
 * satisfied by either publish path alone, so deleting only {@code CampaignService}'s call left it
 * GREEN even though brands publishing through the normal PATCH would then be charged nothing.
 * That defect already shipped once for real: {@code ConfirmLaunchExecutor}'s own class javadoc
 * records "a brand-fee bypass on every campaign launched via Meera's confirm_launch tool ... while
 * the equivalent brand-initiated PATCH /campaigns/{id} path charged the real one."
 *
 * <p><b>T-FEEGATE-0913 follow-up (F-0802 / F-0803) — this gate itself shipped the identical shape
 * of hole twice.</b> The prior version of this class matched on bare method NAME
 * ({@code .contains("update")}), so a reviewer who deleted the real call from
 * {@code CampaignService#update(AuthPrincipal, String, CampaignPatchRequest)} and added an
 * unrelated same-named overload {@code update(Campaign, Workspace)} that itself called {@code
 * chargeOnPublish} left both tests GREEN while the real PATCH path charged nothing (F-0802). It
 * also trusted whatever {@code .class} file happened to sit in {@code target/classes}, so running
 * {@code mvn -o surefire:test} (no compile — this repo's own documented workaround for a broken
 * tree, see {@code reference_run_one_test_when_tree_broken}) after deleting the source call
 * reported GREEN against yesterday's bytecode (F-0803). Two fixes, resolved together:
 *
 * <ul>
 *   <li><b>Exact-signature entry point (closes F-0802).</b> The scan no longer asks "does ANY
 *       method literally named {@code update} call {@code chargeOnPublish}" — it resolves the
 *       real publish-path {@link java.lang.reflect.Method} via reflection (so the parameter types
 *       come from the loaded class, not a hand-typed string) and asks whether THAT exact
 *       name+descriptor pair can reach the charge. An unrelated same-named overload is a different
 *       descriptor and is never visited unless the real entry point actually calls it.
 *   <li><b>Transitive same-class reachability (keeps Extract Method green).</b> A gate that
 *       demanded the call sit textually inside the named method would go RED the moment someone
 *       performs a pure behaviour-preserving Extract Method (pulling the charge into a private
 *       helper that the entry point calls) — training people to "fix" the red by inlining back.
 *       Instead this follows same-class call instructions transitively from the entry point: the
 *       entry point method itself, or any method reachable from it via calls whose owner is the
 *       same class, may contain the real call to {@code chargeOnPublish}.
 *   <li><b>Bytecode-freshness check (closes F-0803).</b> Before trusting the scan, the {@code
 *       .class} file's last-modified time is compared against its {@code .java} source; a class
 *       file older than its source fails loudly instead of being scanned as if it were current.
 * </ul>
 *
 * <p><b>Real call, not a mention.</b> Same technique as {@code EntitlementConformanceTest}: read
 * the actual compiled bytecode of each publish-path class with {@code org.springframework.asm}
 * and record which METHODS contain a real {@code INVOKEVIRTUAL}/{@code INVOKEINTERFACE}/{@code
 * INVOKESPECIAL}/{@code INVOKESTATIC} call instruction targeting {@code
 * BrandCampaignFeeService.chargeOnPublish}. A comment, a javadoc mention, or a string literal
 * naming {@code chargeOnPublish} produces no such instruction and is invisible to this scan — this
 * repo has shipped exactly the "gate satisfied by a comment mentioning the banned/required
 * pattern" defect once already (see {@code reference_grep_gate_matches_its_own_comment}), which is
 * why this does not grep source.
 *
 * <p><b>Each path is its own, independently falsifiable assertion.</b> {@link
 * #campaignServiceUpdatePublishPathChargesTheFee()} names the exact {@code
 * CampaignService#update(AuthPrincipal, String, CampaignPatchRequest)} signature and fails on its
 * own if that entry point can no longer reach the charge; {@link
 * #confirmLaunchExecutorDoExecutePublishPathChargesTheFee()} does the same for the exact {@code
 * ConfirmLaunchExecutor#doExecute(String, String, String, Map)} signature, independently. Deleting
 * either production call site fails only the one test that names it; deleting both fails both.
 *
 * <p><b>What this does NOT achieve — disclosed, not implied.</b> This gate proves that a real call
 * instruction targeting {@code chargeOnPublish} is reachable, within its own declaring class, from
 * the exact named publish-path signature. It does NOT prove the fee is actually charged at
 * runtime, and it cannot: it has no idea whether the call sits behind a guard, or whether that
 * guard is correct. A concrete example that already shipped in this codebase: an always-false
 * guard shaped like {@code if (transitioningToActive && campaign.getStatus() !=
 * CampaignStatus.ACTIVE)} around the charge — where {@code applyPatch} has already flipped the
 * status to {@code ACTIVE} earlier in the same method, so the condition can never be true — leaves
 * the fee permanently uncharged while this gate stays GREEN (the call instruction is still there,
 * still reachable; it just never executes). Only a behavioural test that actually invokes the
 * method and asserts on the resulting side effect catches that. That proof lives in {@code
 * CampaignServiceTest}, {@code CampaignActivationGatesTest}, and {@code ConfirmLaunchExecutorTest}
 * — this class is a static-reachability tripwire that sits alongside those, not a replacement for
 * them.
 *
 * <p>This gate also names the two publish paths that exist today; it does not generically catch a
 * brand-new third path that flips a campaign to ACTIVE without charging the fee. A fully generic
 * version ("any method that transitions a campaign to ACTIVE must, in the same method, also reach
 * chargeOnPublish") was considered and rejected: the two existing paths reach ACTIVE by different
 * bytecode shapes — {@code ConfirmLaunchExecutor.doExecute} does a literal {@code
 * campaign.setStatus(CampaignStatus.ACTIVE)} (a GETSTATIC of the enum constant immediately
 * followed by the setter call, statically detectable), but {@code CampaignService.update} does
 * not: the target status flows through {@code CampaignPatchRequest.status()} — a value
 * deserialized from the request body — into {@code Campaign.applyPatch(...)}, so no method in
 * {@code CampaignService} ever loads a literal {@code GETSTATIC CampaignStatus.ACTIVE} at the
 * point the transition actually happens. If a third publish path is added, a reviewer must add a
 * third named assertion here by hand; nothing automatic will catch its absence.
 */
class BrandFeePublishPathConformanceTest {

    private static final String BRAND_FEE_SERVICE_OWNER = "com/influora/service/BrandCampaignFeeService";
    private static final String CHARGE_ON_PUBLISH = "chargeOnPublish";

    /**
     * F-0848 (Priya ruling c): both publish paths now charge through {@code
     * CampaignActivationGuard.activate}, the only class allowed to call {@code chargeOnPublish}
     * ({@code architecture/CampaignActivationPathTest} T4 proves the guard itself makes that call).
     * A real call to {@code activate} from the named entry point therefore counts as reaching the
     * charge; deleting that call still turns the matching assertion below RED.
     */
    private static final String ACTIVATION_GUARD_OWNER = "com/influora/service/CampaignActivationGuard";
    private static final String ACTIVATE = "activate";

    /** A method identified the way the JVM identifies it: name + full descriptor, never name alone. */
    private record MethodKey(String name, String descriptor) {}

    /** What the scan found for one method declared directly on a scanned class. */
    private record MethodInfo(boolean callsChargeOnPublishDirectly, Set<MethodKey> sameClassCallees) {}

    private static boolean campaignServiceUpdateReachesChargeOnPublish;
    private static boolean confirmLaunchExecutorDoExecuteReachesChargeOnPublish;
    private static String campaignServiceEntryPointDescriptor;
    private static String confirmLaunchExecutorEntryPointDescriptor;

    @BeforeAll
    static void scanCompiledClasses() throws Exception {
        // The parameter types below are the exact real publish-path signatures, read from the
        // source at review time -- but the DESCRIPTOR string used for bytecode matching is never
        // hand-typed: java.lang.reflect.Method + org.springframework.asm.Type.getMethodDescriptor
        // compute it from the loaded class itself, so it always matches what javac actually
        // emitted (erasure, nested-class '$' separators, etc.) rather than a guess.
        Method campaignServiceUpdate =
                CampaignService.class.getDeclaredMethod(
                        "update", AuthPrincipal.class, String.class, CampaignPatchRequest.class);
        Method confirmLaunchDoExecute =
                ConfirmLaunchExecutor.class.getDeclaredMethod(
                        "doExecute", String.class, String.class, String.class, Map.class);

        campaignServiceEntryPointDescriptor = Type.getMethodDescriptor(campaignServiceUpdate);
        confirmLaunchExecutorEntryPointDescriptor = Type.getMethodDescriptor(confirmLaunchDoExecute);

        campaignServiceUpdateReachesChargeOnPublish =
                chargeOnPublishReachableFrom(
                        CampaignService.class,
                        new MethodKey(campaignServiceUpdate.getName(), campaignServiceEntryPointDescriptor));
        confirmLaunchExecutorDoExecuteReachesChargeOnPublish =
                chargeOnPublishReachableFrom(
                        ConfirmLaunchExecutor.class,
                        new MethodKey(confirmLaunchDoExecute.getName(), confirmLaunchExecutorEntryPointDescriptor));
    }

    @Test
    @DisplayName(
            "CampaignService.update(AuthPrincipal,String,CampaignPatchRequest) -- the exact"
                    + " brand-initiated PATCH publish-path signature -- can reach a real call to"
                    + " BrandCampaignFeeService.chargeOnPublish")
    void campaignServiceUpdatePublishPathChargesTheFee() {
        assertThat(campaignServiceUpdateReachesChargeOnPublish)
                .as(
                        "CampaignService#update%s no longer reaches a real call to"
                            + " BrandCampaignFeeService.chargeOnPublish (following same-class calls"
                            + " transitively). This is the exact defect EntitlementConformanceTest's"
                            + " javadoc discloses as its blind spot, closed here for THIS caller"
                            + " independently of ConfirmLaunchExecutor's. Note what this assertion does"
                            + " NOT prove: it does not prove the fee is charged at runtime -- an"
                            + " always-false guard around a reachable call would still pass here (see"
                            + " CampaignServiceTest / CampaignActivationGatesTest for that proof).",
                        campaignServiceEntryPointDescriptor)
                .isTrue();
    }

    @Test
    @DisplayName(
            "ConfirmLaunchExecutor.doExecute(String,String,String,Map) -- the exact Meera"
                    + " confirm_launch publish-path signature -- can reach a real call to"
                    + " BrandCampaignFeeService.chargeOnPublish")
    void confirmLaunchExecutorDoExecutePublishPathChargesTheFee() {
        assertThat(confirmLaunchExecutorDoExecuteReachesChargeOnPublish)
                .as(
                        "ConfirmLaunchExecutor#doExecute%s no longer reaches a real call to"
                            + " BrandCampaignFeeService.chargeOnPublish (following same-class calls"
                            + " transitively). This is precisely the bypass ConfirmLaunchExecutor's own"
                            + " class javadoc records as having already shipped once in production (a"
                            + " silent 0%% fee on every AI-launched campaign while the brand-initiated"
                            + " PATCH path charged the real one). Note what this assertion does NOT"
                            + " prove: it does not prove the fee is charged at runtime -- see"
                            + " ConfirmLaunchExecutorTest for that proof.",
                        confirmLaunchExecutorEntryPointDescriptor)
                .isTrue();
    }

    /**
     * True if the exact {@code entryPoint} (name + descriptor) declared on {@code clazz} either
     * directly contains a real call instruction targeting {@code
     * BrandCampaignFeeService.chargeOnPublish}, or can reach one transitively by following call
     * instructions whose owner is {@code clazz} itself (i.e. a private/package helper the entry
     * point calls, however many levels deep) -- this is what keeps a behaviour-preserving Extract
     * Method green while still refusing to be satisfied by an unrelated same-named overload, which
     * is never called BY the real entry point and so is never visited.
     */
    private static boolean chargeOnPublishReachableFrom(Class<?> clazz, MethodKey entryPoint)
            throws IOException, URISyntaxException {
        var location = clazz.getProtectionDomain().getCodeSource().getLocation();
        Path classpathRoot = new File(location.toURI()).toPath();
        Path classFile = classpathRoot.resolve(clazz.getName().replace('.', '/') + ".class");

        assertClassFresherThanSource(clazz, classpathRoot, classFile);

        byte[] classBytes = Files.readAllBytes(classFile);
        ClassReader reader = new ClassReader(classBytes);
        String selfInternalName = reader.getClassName();
        Map<MethodKey, MethodInfo> methodsByKey = new HashMap<>();

        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodKey key = new MethodKey(name, descriptor);
                        boolean[] callsDirectly = {false};
                        Set<MethodKey> sameClassCallees = new HashSet<>();
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(
                                    int opcode,
                                    String owner,
                                    String calledName,
                                    String calledDesc,
                                    boolean isInterface) {
                                if (owner.equals(BRAND_FEE_SERVICE_OWNER) && calledName.equals(CHARGE_ON_PUBLISH)) {
                                    callsDirectly[0] = true;
                                }
                                if (owner.equals(ACTIVATION_GUARD_OWNER) && calledName.equals(ACTIVATE)) {
                                    callsDirectly[0] = true;
                                }
                                if (owner.equals(selfInternalName)) {
                                    sameClassCallees.add(new MethodKey(calledName, calledDesc));
                                }
                            }

                            @Override
                            public void visitEnd() {
                                methodsByKey.put(key, new MethodInfo(callsDirectly[0], sameClassCallees));
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        Set<MethodKey> visited = new HashSet<>();
        Deque<MethodKey> toVisit = new ArrayDeque<>();
        toVisit.add(entryPoint);
        while (!toVisit.isEmpty()) {
            MethodKey current = toVisit.poll();
            if (!visited.add(current)) {
                continue;
            }
            MethodInfo info = methodsByKey.get(current);
            if (info == null) {
                // The exact entry point (or a callee) isn't declared with this name+descriptor on
                // this class -- e.g. the real signature was renamed or the parameter types
                // changed. Treated as "does not reach", which is the correct RED, not a test-setup
                // error: the whole point of keying on descriptor is that a changed signature must
                // not be silently satisfied by something else of the same name.
                continue;
            }
            if (info.callsChargeOnPublishDirectly()) {
                return true;
            }
            toVisit.addAll(info.sameClassCallees());
        }
        return false;
    }

    /**
     * F-0803: {@code mvn -o surefire:test} runs the test phase without recompiling, so a source
     * edit with no matching compile leaves a STALE {@code .class} file sitting in {@code
     * target/classes} -- the scan above would then read yesterday's bytecode and report GREEN
     * against code that no longer exists on disk. Fail loudly instead of silently trusting
     * whatever happens to be compiled.
     */
    private static void assertClassFresherThanSource(Class<?> clazz, Path classpathRoot, Path classFile)
            throws IOException {
        assertThat(Files.exists(classFile))
                .as("compiled class file missing at %s -- run `mvn -o test-compile` first", classFile)
                .isTrue();

        // classpathRoot is ".../influora-api/target/classes"; walk up to the module root
        // (".../influora-api") the same two levels every Maven module uses, then back down into
        // its source tree -- no assumption beyond the standard Maven layout this repo already
        // uses everywhere else (see CLAUDE-level docs: "influora-api is the only pom").
        Path moduleRoot = classpathRoot.getParent().getParent();
        Path sourceFile =
                moduleRoot.resolve("src/main/java").resolve(clazz.getName().replace('.', '/') + ".java");
        assertThat(Files.exists(sourceFile))
                .as("expected source file not found at %s -- freshness check cannot run", sourceFile)
                .isTrue();

        FileTime classModified = Files.getLastModifiedTime(classFile);
        FileTime sourceModified = Files.getLastModifiedTime(sourceFile);
        assertThat(classModified)
                .as(
                        "%s (compiled %s) is STALE relative to its source %s (last edited %s) -- this"
                            + " gate reads target/classes directly, and a source edit with no recompile"
                            + " (e.g. running `mvn -o surefire:test` right after editing the .java, this"
                            + " repo's own documented workaround for an otherwise-broken tree) leaves"
                            + " yesterday's bytecode in place, which this scan would otherwise report as"
                            + " GREEN against code that no longer exists. Run `mvn -o test-compile` (or a"
                            + " full `mvn test`) before trusting this gate's result.",
                        classFile,
                        classModified,
                        sourceFile,
                        sourceModified)
                .isGreaterThanOrEqualTo(sourceModified);
    }
}
