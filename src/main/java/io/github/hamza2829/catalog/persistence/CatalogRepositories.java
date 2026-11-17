package io.github.hamza2829.catalog.persistence;

import io.github.hamza2829.catalog.model.RejectionCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface Products extends JpaRepository<StoredProduct, String> {
    @Query("""
            select product from StoredProduct product
            where (:query = '' or locate(:query, product.searchText) > 0)
              and (:category = '' or product.category = :category)
              and (:status = '' or product.lifecycleStatus = :status)
            """)
    Page<StoredProduct> search(String query, String category, String status, Pageable pageable);
}

interface ProductChanges extends JpaRepository<StoredChange, Long> {
    Page<StoredChange> findByArticleNumber(String articleNumber, Pageable pageable);
}

interface Imports extends JpaRepository<StoredImport, UUID> {
    Optional<StoredImport> findByDigest(String digest);
}

interface DeadLetters extends JpaRepository<DeadLetter, UUID> {
    Page<DeadLetter> findByResolvedAtIsNull(Pageable pageable);

    Page<DeadLetter> findByReasonAndResolvedAtIsNull(RejectionCode reason, Pageable pageable);

    Page<DeadLetter> findByReason(RejectionCode reason, Pageable pageable);
}
