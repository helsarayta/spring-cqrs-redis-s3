package com.example.read.service;

import com.example.common.dto.ProductPayload;
import com.example.common.dto.ProductStatus;
import com.example.read.api.dto.PageResponse;
import com.example.read.domain.ProductReadModel;
import com.example.read.repository.ProductReadModelRepository;
import com.example.read.repository.ProductSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Seluruh akses database milik jalur baca.
 *
 * <p>Dipisahkan dari {@link ProductQueryService} karena dua alasan yang sama-sama nyata:
 *
 * <p><b>Pertama, {@code @Transactional} bekerja lewat proxy.</b> Kalau method-method ini
 * berada di {@code ProductQueryService} dan dipanggil dari method lain di kelas yang sama,
 * proxy-nya dilewati dan anotasinya tidak melakukan apa-apa — {@code readOnly} tidak berlaku
 * dan setiap query berjalan dalam transaksinya masing-masing tanpa ada yang menyadarinya.
 *
 * <p><b>Kedua, batas transaksi jadi sesempit mungkin.</b> Alur cache-aside mengandung
 * panggilan ke Redis dan penantian singkat untuk kunci single-flight. Kalau semua itu berada
 * di dalam satu transaksi, koneksi database ikut tertahan selama operasi yang sama sekali
 * tidak membutuhkannya — dan pada beban tinggi, pool koneksi habis justru karena menunggu
 * Redis.
 *
 * <p>Semua method mengembalikan {@link ProductPayload}, bukan entity, supaya tidak ada objek
 * ter-manage Hibernate yang bocor ke luar batas transaksi.
 */
@Component
@RequiredArgsConstructor
public class ProductReadDao {

    private final ProductReadModelRepository repository;

    /** Produk berstatus DELETED diperlakukan sebagai tidak ada. */
    @Transactional(readOnly = true)
    public Optional<ProductPayload> findActiveById(UUID id) {
        return repository.findById(id)
                .filter(model -> model.getStatus() != ProductStatus.DELETED)
                .map(ReadModelMapper::toPayload);
    }

    @Transactional(readOnly = true)
    public Optional<ProductPayload> findActiveBySku(String sku) {
        return repository.findBySku(sku)
                .filter(model -> model.getStatus() != ProductStatus.DELETED)
                .map(ReadModelMapper::toPayload);
    }

    /**
     * Satu transaksi mencakup query hitung dan query isi halaman sekaligus, sehingga
     * {@code totalElements} dan {@code content} berasal dari keadaan database yang sama.
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductPayload> search(ProductStatus status, String q,
                                               BigDecimal minPrice, BigDecimal maxPrice,
                                               Pageable pageable) {
        Page<ProductReadModel> page = repository.findAll(
                ProductSpecifications.build(status, q, minPrice, maxPrice), pageable);
        return PageResponse.from(page, ReadModelMapper::toPayload);
    }
}
