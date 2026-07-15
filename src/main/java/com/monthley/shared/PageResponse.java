package com.monthley.shared;

import java.util.List;

/** Balasan senarai berhalaman — padan Page<T> dalam handoff Angular. */
public record PageResponse<T>(List<T> items, long total, int page, int pageSize) {

    public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> p) {
        return new PageResponse<>(p.getContent(), p.getTotalElements(),
                p.getNumber(), p.getSize());
    }
}
