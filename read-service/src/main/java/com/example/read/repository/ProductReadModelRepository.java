package com.example.read.repository;

import com.example.read.domain.ProductReadModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code JpaSpecificationExecutor} dipakai, bukan satu {@code @Query} panjang dengan pola
 * {@code (:param IS NULL OR ...)}. Pola itu memaksa database mengevaluasi seluruh cabang
 * filter pada setiap query dan sering membuat perencana query mengabaikan index; Specification
 * hanya menyusun kondisi yang benar-benar dikirim klien.
 */
public interface ProductReadModelRepository
        extends JpaRepository<ProductReadModel, UUID>, JpaSpecificationExecutor<ProductReadModel> {

    Optional<ProductReadModel> findBySku(String sku);
}
