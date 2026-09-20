package com.influora.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.domain.entity.Campaign;
import com.influora.service.CampaignActivationGuard;
import com.influora.service.CampaignService;
import com.influora.service.meera.tool.ConfirmLaunchExecutor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

/**
 * F-0848 T4 + T5 (Priya ruling c): {@link CampaignActivationGuard} is the ONLY production code that
 * may (T4) call {@code BrandCampaignFeeService.chargeOnPublish} or (T5) set a campaign's status to
 * {@code CampaignStatus.ACTIVE}. A fourth activation path cannot be added silently.
 *
 * <p><b>Not ArchUnit.</b> ArchUnit is not a dependency in {@code pom.xml}, and adding one needs
 * approval. This reads the COMPILED bytecode of every class under {@code target/classes} with
 * {@code org.springframework.asm} (already on the classpath, same technique as {@code
 * BrandFeePublishPathConformanceTest}). Bytecode rather than a source grep because a comment or
 * javadoc naming the banned call produces no instruction (a source-grep gate in this repo once
 * matched its own comment), and a static import of {@code ACTIVE} compiles to the same GETSTATIC.
 *
 * <p><b>F-0872 fix — T5 is now backstopped by a structural entity guard, not just this scan.</b>
 * Meera's falsification found this scan alone insufficient: mutation B5 (ACTIVE reaching {@code
 * Campaign.setStatus} through a variable instead of a literal — invisible to the literal-adjacency
 * check below) and B6 ({@code FestivalSponsorProvisioningService} minting a campaign already ACTIVE
 * via {@code Campaign.builder().status(CampaignStatus.valueOf("ACTIVE")).build()}, a path this scan
 * never covered at all) both left the suite green. {@code Campaign.setStatus} and {@code
 * Campaign.applyPatch} now refuse ACTIVE UNCONDITIONALLY at runtime (any source, literal or
 * variable). {@code Campaign.Builder.build()} does NOT (a builder-level refusal broke test
 * fixtures and was reverted — see the note in {@code Builder.build()}); a new campaign minted
 * ACTIVE through the builder is caught only by T6 below, for production classes. B5 is shown red
 * in {@code CampaignActivationInvariantTest}.
 *
 * <p><b>T5 detection and its disclosed limit (this scan's own layer only).</b> A setter is flagged
 * when a {@code GETSTATIC CampaignStatus.ACTIVE} is IMMEDIATELY followed by a call to {@code
 * Campaign.setStatus} or {@code Campaign.Builder.status} — the shape {@code
 * setStatus(CampaignStatus.ACTIVE)} and {@code .status(CampaignStatus.ACTIVE)} compile to. It does
 * NOT see ACTIVE arriving through a variable (e.g. {@code applyPatch(req.status())} or a ternary) —
 * that gap is why the entity-level check above exists. It also flags any call to {@code
 * Campaign.activateForPublish()} outside the guard: unlike {@code setStatus}, that method takes no
 * status argument at all, so every call site unambiguously means "set this campaign ACTIVE" and no
 * literal-adjacency heuristic is needed to catch it. {@code CampaignService.update} used to pass
 * ACTIVE through {@code applyPatch} directly; it now passes {@code null} to {@code applyPatch}
 * whenever the target is ACTIVE, and {@code create} hard-codes DRAFT. Those two are pinned
 * behaviourally by {@code CampaignActivationGuardTest} / {@code CampaignCreateStatusGateTest}, not
 * by this scan.
 *
 * <p><b>Not vacuous.</b> Each rule also asserts it DID find the guard's own call/setter, so a scan
 * that reads nothing (wrong directory, renamed owner string) goes red instead of passing on "".
 */
class CampaignActivationPathTest {

    private static final String FEE_SERVICE = "com/influora/service/BrandCampaignFeeService";
    private static final String CHARGE = "chargeOnPublish";
    private static final String CAMPAIGN_STATUS = "com/influora/domain/enums/CampaignStatus";
    private static final String CAMPAIGN = "com/influora/domain/entity/Campaign";
    private static final String CAMPAIGN_BUILDER = "com/influora/domain/entity/Campaign$Builder";
    private static final String ACTIVATE_FOR_PUBLISH = "activateForPublish";
    private static final String GUARD =
            CampaignActivationGuard.class.getName().replace('.', '/');

    /** "owner#method" for every chargeOnPublish call site found. */
    private static final List<String> chargeCallSites = new ArrayList<>();
    /** "owner#method" for every literal ACTIVE setter found. */
    private static final List<String> activeSetterSites = new ArrayList<>();
    /**
     * F-0872 T6 — "owner#method" for every {@code Campaign.Builder.status(...)} call site in
     * production code (test classes are never on this scan's path — see class javadoc) whose
     * argument was NOT a literal {@code CampaignStatus} constant immediately before the call. Closes
     * Meera's B6 mutation ({@code FestivalSponsorProvisioningService} minting a campaign via {@code
     * Campaign.builder().status(CampaignStatus.valueOf("ACTIVE")).build()}) — bytecode cannot prove
     * WHICH status a dynamic value resolves to, so this rule is deliberately the wider "must be a
     * compile-time constant at all" bar rather than trying to spot ACTIVE specifically through a
     * variable, which is undecidable from bytecode alone.
     */
    private static final List<String> nonLiteralBuilderStatusSites = new ArrayList<>();
    private static int classesScanned;

    @BeforeAll
    static void scan() throws Exception {
        Path classesRoot =
                new File(Campaign.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toPath();
        assertFresh(classesRoot, CampaignActivationGuard.class);
        assertFresh(classesRoot, CampaignService.class);
        assertFresh(classesRoot, ConfirmLaunchExecutor.class);

        try (Stream<Path> files = Files.walk(classesRoot)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                scanClass(Files.readAllBytes(p));
                classesScanned++;
            }
        }
    }

    @Test
    @DisplayName("T4 only CampaignActivationGuard calls BrandCampaignFeeService.chargeOnPublish")
    void onlyGuardChargesThePublishFee() {
        assertThat(classesScanned).as("classes scanned under target/classes").isGreaterThan(100);
        assertThat(chargeCallSites)
                .as("the scan must see the guard's own call, or it is reading nothing")
                .anyMatch(site -> site.startsWith(GUARD + "#"));
        assertThat(chargeCallSites)
                .as(
                        "chargeOnPublish call sites outside CampaignActivationGuard -- route this"
                                + " activation through CampaignActivationGuard.activate instead")
                .allMatch(site -> isGuard(site));
    }

    @Test
    @DisplayName("T5 no production code outside CampaignActivationGuard sets CampaignStatus.ACTIVE")
    void onlyGuardSetsActive() {
        assertThat(activeSetterSites)
                .as("the scan must see the guard's own setter, or it is reading nothing")
                .anyMatch(site -> site.startsWith(GUARD + "#"));
        assertThat(activeSetterSites)
                .as(
                        "setStatus(ACTIVE)/.status(ACTIVE)/activateForPublish() outside"
                                + " CampaignActivationGuard -- a campaign must never go ACTIVE without secured"
                                + " funds and the fee")
                .allMatch(site -> isGuard(site));
    }

    @Test
    @DisplayName("T6 production code never builds a Campaign with a non-literal CampaignStatus (B6)")
    void builderStatusIsAlwaysALiteral() {
        assertThat(classesScanned).as("classes scanned under target/classes").isGreaterThan(100);
        assertThat(nonLiteralBuilderStatusSites)
                .as(
                        "Campaign.Builder.status(...) called with a value that is not a compile-time"
                                + " CampaignStatus constant -- a new campaign must always be built as a literal"
                                + " status (DRAFT in every production call site today); a dynamic value here"
                                + " (e.g. CampaignStatus.valueOf(...)) can silently mint a campaign already"
                                + " ACTIVE with no funds check and no fee (F-0872 / Meera B6)")
                .isEmpty();
    }

    private static boolean isGuard(String site) {
        String owner = site.substring(0, site.indexOf('#'));
        return owner.equals(GUARD) || owner.startsWith(GUARD + "$");
    }

    private static void scanClass(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        String owner = reader.getClassName();
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String desc, String signature, String[] exceptions) {
                        String site = owner + "#" + name + desc;
                        return new MethodVisitor(Opcodes.ASM9) {
                            private boolean lastWasActiveConstant;
                            private boolean lastWasAnyStatusConstant;

                            private void other() {
                                lastWasActiveConstant = false;
                                lastWasAnyStatusConstant = false;
                            }

                            @Override
                            public void visitFieldInsn(int opcode, String fOwner, String fName, String fDesc) {
                                boolean isStatusConstant =
                                        opcode == Opcodes.GETSTATIC && fOwner.equals(CAMPAIGN_STATUS);
                                lastWasActiveConstant = isStatusConstant && fName.equals("ACTIVE");
                                lastWasAnyStatusConstant = isStatusConstant;
                            }

                            @Override
                            public void visitMethodInsn(
                                    int opcode, String mOwner, String mName, String mDesc, boolean itf) {
                                if (mOwner.equals(FEE_SERVICE) && mName.equals(CHARGE)) {
                                    chargeCallSites.add(site);
                                }
                                if (lastWasActiveConstant
                                        && ((mOwner.equals(CAMPAIGN) && mName.equals("setStatus"))
                                                || (mOwner.equals(CAMPAIGN_BUILDER) && mName.equals("status")))) {
                                    activeSetterSites.add(site);
                                }
                                // F-0872: activateForPublish() takes no status argument at all, so unlike
                                // setStatus/.status it needs no literal-adjacency check -- every call site
                                // unambiguously sets ACTIVE.
                                if (mOwner.equals(CAMPAIGN) && mName.equals(ACTIVATE_FOR_PUBLISH)) {
                                    activeSetterSites.add(site);
                                }
                                // F-0872 T6 (B6): a Builder.status(...) call whose argument was NOT a literal
                                // CampaignStatus constant -- e.g. CampaignStatus.valueOf(...) stored in a
                                // local and passed in -- can silently mint an already-ACTIVE campaign and is
                                // undecidable from bytecode which enum value it actually is, so ANY dynamic
                                // value here is flagged rather than trying to prove it specifically ACTIVE.
                                if (mOwner.equals(CAMPAIGN_BUILDER)
                                        && mName.equals("status")
                                        && !lastWasAnyStatusConstant) {
                                    nonLiteralBuilderStatusSites.add(site);
                                }
                                other();
                            }

                            @Override
                            public void visitInvokeDynamicInsn(
                                    String iName, String iDesc, Handle bsm, Object... bsmArgs) {
                                // A method reference (feeService::chargeOnPublish) is a call site too.
                                for (Object arg : bsmArgs) {
                                    if (arg instanceof Handle h
                                            && h.getOwner().equals(FEE_SERVICE)
                                            && h.getName().equals(CHARGE)) {
                                        chargeCallSites.add(site);
                                    }
                                }
                                other();
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                other();
                            }

                            @Override
                            public void visitIntInsn(int opcode, int operand) {
                                other();
                            }

                            @Override
                            public void visitVarInsn(int opcode, int varIndex) {
                                other();
                            }

                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                other();
                            }

                            @Override
                            public void visitJumpInsn(int opcode, Label label) {
                                other();
                            }

                            @Override
                            public void visitLdcInsn(Object value) {
                                other();
                            }

                            @Override
                            public void visitIincInsn(int varIndex, int increment) {
                                other();
                            }

                            // EV-005 falsification: a label is a branch merge point. Without this, the
                            // ternary `.status(x != null ? x : CampaignStatus.DRAFT)` compiles to
                            // `...; GETSTATIC DRAFT; <label>; INVOKEVIRTUAL status` and was read as a
                            // literal, so a dynamic (possibly ACTIVE) status passed T6 green.
                            @Override
                            public void visitLabel(Label label) {
                                other();
                            }

                            @Override
                            public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                                other();
                            }

                            @Override
                            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                                other();
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /**
     * Bytecode is only evidence if it was compiled from the current source (F-0803: {@code mvn -o
     * surefire:test} after a source edit scans yesterday's classes).
     */
    private static void assertFresh(Path classesRoot, Class<?> clazz) throws IOException {
        String rel = clazz.getName().replace('.', '/');
        Path classFile = classesRoot.resolve(rel + ".class");
        Path sourceFile = classesRoot.getParent().getParent().resolve("src/main/java").resolve(rel + ".java");
        assertThat(Files.exists(classFile)).as("missing %s", classFile).isTrue();
        assertThat(Files.exists(sourceFile)).as("missing %s", sourceFile).isTrue();
        assertThat(Files.getLastModifiedTime(classFile))
                .as("%s is older than its source -- recompile before trusting this gate", classFile)
                .isGreaterThanOrEqualTo(Files.getLastModifiedTime(sourceFile));
    }
}
