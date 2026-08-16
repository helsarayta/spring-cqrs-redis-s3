package com.example.read.repository;

import com.example.common.dto.ProductStatus;
import com.example.read.domain.ProductReadModel;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Penyusun kondisi filter untuk daftar produk. */
public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    public static Specification<ProductReadModel> build(ProductStatus status, String q,
                                                        BigDecimal minPrice, BigDecimal maxPrice) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            } else {
                // Tanpa filter status eksplisit, produk terhapus tidak ikut ditampilkan.
                // Soft delete akan kehilangan maknanya kalau barisnya tetap muncul di daftar.
                predicates.add(cb.notEqual(root.get("status"), ProductStatus.DELETED));
            }

            if (q != null && !q.isBlank()) {
                // LOWER(name) cocok dengan index ix_prm_name_lower.
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + q.toLowerCase() + "%"));
            }
            if (minPrice != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("price"), minPrice));
            }
            if (maxPrice != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("price"), maxPrice));
            }

            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
}
