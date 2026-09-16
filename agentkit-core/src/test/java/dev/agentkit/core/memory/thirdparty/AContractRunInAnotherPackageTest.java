package dev.agentkit.core.memory.thirdparty;

import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.core.memory.MemoryStoreContract;

/**
 * The {@link MemoryStoreContract} run from a package that is not the one it lives in.
 *
 * <p>This is the only reason this class exists, and it is not ceremony. The whole point of
 * the TCK is that somebody outside this repository extends it, and everything about that is
 * different from what the two runners beside {@code MemoryStoreContract} exercise: their
 * package gives them access this one does not have. If the hooks or the clauses were
 * package-private, they would still compile and run <em>there</em> and quietly do nothing
 * <em>here</em> — which is #153's own defect, one rule and a runner nobody checked, in the
 * class written to fix it.
 *
 * <p>So this asserts by existing. It contributes no assertions of its own; what it proves
 * is that {@link MemoryStoreContract} is extensible, that its clauses are discovered from
 * out here, and that the count of tests it runs matches the count the in-package runner
 * gets. The store under it is the in-memory one because the medium is beside the point: the
 * question is the visibility, not the implementation.
 *
 * <p>It does not prove the packaging. That the test-jar carries these classes and nothing
 * else is {@code agentkit-core/pom.xml}'s job, and it is checked by looking at the jar.
 */
class AContractRunInAnotherPackageTest extends MemoryStoreContract {

    @Override
    protected MemoryStore newStore() {
        return MemoryStore.inMemory();
    }
}
