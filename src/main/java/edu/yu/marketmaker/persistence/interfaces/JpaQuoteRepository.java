package edu.yu.marketmaker.persistence.interfaces;

import edu.yu.marketmaker.persistence.BaseJpaRepository;
import edu.yu.marketmaker.persistence.QuoteEntity;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * JPA Repository interface for QuoteEntity persistence.
 * Spring Data JPA requires concrete interfaces to create proxy implementations.
 */
@Repository
public interface JpaQuoteRepository extends BaseJpaRepository<QuoteEntity, String> {
    // Multiple rows per symbol can accumulate (each MM-generated quote inserts
    // a new quote_id row). MapStore.load needs a single result, so pick the
    // most recently expiring one — that's the freshest version of the symbol.
    Optional<QuoteEntity> findFirstBySymbolOrderByExpiresAtDesc(String symbol);
    void deleteBySymbol(String symbol);
    List<QuoteEntity> findAllBySymbolIn(Collection<String> symbols);
}
