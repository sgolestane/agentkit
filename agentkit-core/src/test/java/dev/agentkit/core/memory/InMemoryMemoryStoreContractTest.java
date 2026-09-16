package dev.agentkit.core.memory;

/**
 * {@link InMemoryMemoryStore} against the contract every implementation owes.
 *
 * <p>The base contract and not {@link MediumBackedMemoryStoreContract}: nothing else can
 * write into a {@code TreeMap}, so the planted-entry clauses would be vacuous here, and a
 * vacuous clause that passes reads exactly like one that checked something.
 */
class InMemoryMemoryStoreContractTest extends MemoryStoreContract {

    @Override
    protected MemoryStore newStore() {
        return new InMemoryMemoryStore();
    }
}
