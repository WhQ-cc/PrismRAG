package com.prism.rag.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * PrismRAG 启动类。
 *
 * <h3>关于两个扫描注解</h3>
 * <p>本项目是多模块工程，代码分散在 7 个包里，但启动类只写在 {@code prism-bootstrap}。
 * Spring Boot 默认<b>只扫描启动类所在包及其子包</b>，
 * 如果不额外声明，其它 6 个模块的 Bean 都不会被注册（表现为 "找不到 ChatClient 之类的 Bean"）。</p>
 *
 * <ul>
 *   <li>{@code scanBasePackages = "com.prism.rag"}：扫描所有模块的组件
 *       （Service / Repository / Configuration / Tool …）。</li>
 *   <li>{@code @EntityScan("com.prism.rag.rag.domain")}：指定 JPA 实体所在包。
 *       原因：Hibernate 找实体用的是"主应用类所在包"，不是组件扫描范围，
 *       不显式指定就会报 "Not a managed type"。</li>
 *   <li>{@code @EnableJpaRepositories("com.prism.rag.rag.repository")}：指定仓储接口所在包。
 *       <b>这一条最容易漏</b>：Spring Data 扫描仓储用的也是"主应用类所在包"，
 *       漏掉它启动日志里会出现 "Found 0 JPA repository interfaces"，
 *       然后报 "No qualifying bean of type 'XxxRepository' available"。</li>
 * </ul>
 *
 * <p>这三个注解是"多模块 Spring Boot 项目最常见的三个坑"，记住它们能省很多时间。</p>
 */
@SpringBootApplication(scanBasePackages = "com.prism.rag")
@EntityScan(basePackages = "com.prism.rag.rag.domain")
@EnableJpaRepositories(basePackages = "com.prism.rag.rag.repository")
@ComponentScan(basePackages = "com.prism.rag")
public class PrismRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrismRagApplication.class, args);
    }
}
