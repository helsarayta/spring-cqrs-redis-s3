package com.example.read.api.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Bentuk halaman yang stabil untuk API.
 *
 * <p>Sengaja tidak mengembalikan {@code Page} milik Spring Data secara langsung: bentuk
 * JSON-nya adalah detail internal pustaka yang pernah berubah antar versi, dan klien tidak
 * seharusnya ikut terdampak saat versi Spring dinaikkan. Bentuk ini juga aman disimpan
 * ke cache karena tidak tergantung kelas internal apa pun.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(
                content.stream().map(mapper).toList(),
                page, size, totalElements, totalPages, first, last);
    }
}
