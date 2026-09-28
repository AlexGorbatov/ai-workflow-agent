package com.altronixsoft.workflow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;

/**
 * The architecture invariants from docs/GUIDE_RU.md §3.3 that can be checked on bytecode. Rules allow
 * empty packages so they hold from M0 and bite as soon as code arrives; M1 adds the rules for steps.
 */
@AnalyzeClasses(packages = "com.altronixsoft.workflow", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule onlyTheLlmPackageTalksToModels = noClasses()
            .that()
            .resideOutsideOfPackage("..workflow.llm..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.ai.chat..")
            .because("invariant 1: a model is called only inside the Understand, Respond and Investigator steps")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule moneyAndPolicyNeverDependOnAModel = noClasses()
            .that()
            .resideInAnyPackage("..workflow.quote..", "..workflow.policy..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..workflow.llm..", "org.springframework.ai..")
            .because("invariant 2: prices and policy are deterministic code and YAML, never a model")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule onlyTheToolsPackageReachesExternalSystems = noClasses()
            .that()
            .resideOutsideOfPackage("..workflow.tools..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.modelcontextprotocol..",
                    "org.springframework.ai.mcp..",
                    "org.springframework.mail..",
                    "jakarta.mail..")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.client.RestClient")
            .because("invariant 5: every external call goes through ToolGateway")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule noVendorModelSdk = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.ai.openai..", "com.openai..")
            .because("invariant 6: business logic depends only on Spring AI abstractions; the provider is a profile")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule timeComesFromAnInjectedClock = noClasses()
            .should()
            .callMethod(Instant.class, "now")
            .because("tests must control time: inject java.time.Clock")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule constructorInjectionOnly = NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true);

    @ArchTest
    static final ArchRule slf4jLoggingOnly = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.allowEmptyShould(true);
}
