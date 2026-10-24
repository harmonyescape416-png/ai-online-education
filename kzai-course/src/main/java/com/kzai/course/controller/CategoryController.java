package com.kzai.course.controller;

import com.kzai.common.result.R;
import com.kzai.course.service.CategoryService;
import com.kzai.course.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类公开接口（M3）：仅返回启用分类，供学员端课程浏览使用。
 */
@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping("/tree")
    public R<List<CategoryVO>> tree() {
        return R.ok(categoryService.tree(true));
    }
}
