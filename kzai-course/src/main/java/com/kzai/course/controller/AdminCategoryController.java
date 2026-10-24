package com.kzai.course.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.course.dto.CategorySaveRequest;
import com.kzai.course.service.CategoryService;
import com.kzai.course.vo.CategoryVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员分类管理（M3）：两级分类树、新增/修改、排序、启停、删除前关联校验。
 */
@RestController
@RequestMapping("/admin/categories")
@RequireRoles({Constants.ROLE_ADMIN, Constants.ROLE_STAFF})
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;

    @GetMapping("/tree")
    public R<List<CategoryVO>> tree() {
        return R.ok(categoryService.tree(false));
    }

    @PostMapping
    public R<Long> create(@Valid @RequestBody CategorySaveRequest request) {
        return R.ok(categoryService.create(request));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody CategorySaveRequest request) {
        categoryService.update(id, request);
        return R.ok();
    }

    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable("id") Long id, @RequestParam("status") Integer status) {
        categoryService.updateStatus(id, status);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        categoryService.delete(id);
        return R.ok();
    }
}
