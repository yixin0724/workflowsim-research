package org.workflowsim.experiment.registry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.ReplicaCatalog.FileSystem;

/**
 * AlgorithmDeclaration semantic contract tests (2026-10-26, BAT-3 / M11 killer).
 *
 * <p>coversFileSystem() is the exact clause exercised by the runner's fail-fast matrix. These
 * tests lock its branch face so an inverted ANY-scope cannot survive the mutation gate (the
 * grad-student exercise asserts the same behavior end-to-end from the experiments module).</p>
 */
class AlgorithmDeclarationTest {

    @Test
    void anyScopeCoversBothSupportedFileSystems() {
        AlgorithmDeclaration declaration = new AlgorithmDeclaration.Builder()
                .fileSystemScope(AlgorithmDeclaration.FileSystemScope.ANY)
                .description("any")
                .build();
        assertTrue(declaration.coversFileSystem(FileSystem.LOCAL), "ANY must cover LOCAL");
        assertTrue(declaration.coversFileSystem(FileSystem.SHARED), "ANY must cover SHARED");
        assertTrue(declaration.coversFileSystem(ReplicaCatalog.FileSystem.LOCAL));
    }

    @Test
    void localOnlyScopeCoversOnlyLocalAndNullIsNeverCovered() {
        AlgorithmDeclaration declaration = new AlgorithmDeclaration.Builder()
                .fileSystemScope(AlgorithmDeclaration.FileSystemScope.LOCAL_ONLY)
                .description("local")
                .build();
        assertTrue(declaration.coversFileSystem(FileSystem.LOCAL));
        assertFalse(declaration.coversFileSystem(FileSystem.SHARED), "LOCAL_ONLY must not cover SHARED");
        assertFalse(declaration.coversFileSystem(null), "null is never covered");
    }

    @Test
    void sharedOnlyScopeCoversOnlyShared() {
        AlgorithmDeclaration declaration = new AlgorithmDeclaration.Builder()
                .fileSystemScope(AlgorithmDeclaration.FileSystemScope.SHARED_ONLY)
                .description("shared")
                .build();
        assertTrue(declaration.coversFileSystem(FileSystem.SHARED));
        assertFalse(declaration.coversFileSystem(FileSystem.LOCAL), "SHARED_ONLY must not cover LOCAL");
        assertFalse(declaration.coversFileSystem(null));
    }

    @Test
    void declarationMetadataIsImmutableAndValidated() {
        AlgorithmDeclaration declaration = new AlgorithmDeclaration.Builder()
                .acceptsTaskCostMatrix(true)
                .allowsFailureModel(false)
                .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                .description("matrix-friendly")
                .build();
        assertTrue(declaration.acceptsTaskCostMatrix());
        assertFalse(declaration.allowsFailureModel());
        assertTrue(declaration.getAllowedDataMovementKinds().contains("LEGACY_WORKFLOWSIM_V1"));
        assertFalse(declaration.getFileSystemScope() == null);
    }

    @Test
    void energyFieldsDefaultOffAndImplicationsAreEnforced() {
        // 默认全关——既有注册零影响
        AlgorithmDeclaration plain = new AlgorithmDeclaration.Builder()
                .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                .build();
        assertFalse(plain.isEnergyAware());
        assertFalse(plain.isPowerModelRequired());
        assertTrue(plain.getEnergyObjectiveWeight() == 0.0);

        // powerModelRequired 蕴含 energyAware
        AlgorithmDeclaration required =
                new AlgorithmDeclaration.Builder().powerModelRequired(true).build();
        assertTrue(required.isPowerModelRequired());
        assertTrue(required.isEnergyAware());

        // 正权重蕴含 energyAware
        AlgorithmDeclaration weighted =
                new AlgorithmDeclaration.Builder().energyObjectiveWeight(1.5).build();
        assertTrue(weighted.isEnergyAware());
        assertTrue(weighted.getEnergyObjectiveWeight() == 1.5);
    }

    @Test
    void energyWeightMustBeFiniteNonNegative() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AlgorithmDeclaration.Builder()
                        .energyObjectiveWeight(-0.1)
                        .build(),
                "negative weight must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new AlgorithmDeclaration.Builder()
                        .energyObjectiveWeight(Double.POSITIVE_INFINITY)
                        .build(),
                "infinite weight must be rejected");
    }

    @Test
    void carbonFieldsDefaultOffAndImplicationsAreEnforced() {
        // BAT-C5（2026-10-28）：默认全关——既有注册零影响；正权重蕴含 carbonAware 且
        // 因 v1 两种碳核算都骑在能量链上（设计文档 §2.2）同时蕴含 energyAware。
        AlgorithmDeclaration plain = new AlgorithmDeclaration.Builder()
                .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                .build();
        assertFalse(plain.isCarbonAware());
        assertTrue(plain.getCarbonObjectiveWeight() == 0.0);
        assertFalse(plain.isEnergyAware());

        AlgorithmDeclaration carbonAwareOnly =
                new AlgorithmDeclaration.Builder().carbonAware(true).build();
        assertTrue(carbonAwareOnly.isCarbonAware());
        assertFalse(carbonAwareOnly.isEnergyAware(), "carbonAware alone does not imply energyAware");

        AlgorithmDeclaration weighted =
                new AlgorithmDeclaration.Builder().carbonObjectiveWeight(1.0).build();
        assertTrue(weighted.isCarbonAware());
        assertTrue(weighted.isEnergyAware(), "positive carbon weight rides the energy chain");
        assertTrue(weighted.getCarbonObjectiveWeight() == 1.0);

        // 声明链接：注册演示器 = powerModelRequired + carbonObjectiveWeight 1.0 + energyAware
        AlgorithmDeclaration demonstrator = new AlgorithmDeclaration.Builder()
                .powerModelRequired(true)
                .carbonObjectiveWeight(1.0)
                .build();
        assertTrue(demonstrator.isPowerModelRequired());
        assertTrue(demonstrator.isEnergyAware());
        assertTrue(demonstrator.isCarbonAware());
    }

    @Test
    void carbonWeightMustBeFiniteNonNegative() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AlgorithmDeclaration.Builder()
                        .carbonObjectiveWeight(-0.1)
                        .build(),
                "negative carbon weight must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new AlgorithmDeclaration.Builder()
                        .carbonObjectiveWeight(Double.POSITIVE_INFINITY)
                        .build(),
                "infinite carbon weight must be rejected");
    }

    @Test
    void registryRejectsPowerRequiredAlgorithmOnUnpoweredPlatform() {
        // SPI 注册的 EnergyTestSchedulerProvider（simulator 测试 classpath）声明 powerModelRequired=true.
        // 无功率平台 → powerModelRequired 拒绝
        IllegalArgumentException unpowered = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        "ENERGY-TEST-REQUIRED",
                        new AlgorithmRegistry.ValidationInput(
                                FileSystem.SHARED,
                                null,
                                false,
                                null,
                                org.workflowsim.utils.Parameters.PlanningAlgorithm.INVALID,
                                false)),
                "power-required algorithm must be rejected on unpowered platform");
        assertTrue(
                unpowered.getMessage().contains("powerModelRequired"),
                "message must cite the power clause: " + unpowered.getMessage());
        // 带功率平台 → 放行
        AlgorithmRegistry.validate(
                "ENERGY-TEST-REQUIRED",
                new AlgorithmRegistry.ValidationInput(
                        FileSystem.SHARED,
                        null,
                        false,
                        null,
                        org.workflowsim.utils.Parameters.PlanningAlgorithm.INVALID,
                        true));
    }
}
