## ADDED Requirements

### Requirement: JWT 过滤器 SHALL 把 userId 写入 SecurityContextHolder

`JwtAuthenticationFilter` SHALL 在解析出合法 JWT 后，除了往 `HttpServletRequest.attribute` 写 `userId`，还 MUST 把 `UsernamePasswordAuthenticationToken(userId, null, [ROLE_USER])` 写入 `SecurityContextHolder`，为后续基于身份的授权 / `@AuthenticationPrincipal` 打地基。

#### Scenario: 合法 JWT 场景

- **WHEN** 请求携带合法 `Authorization: Bearer <jwt>` 头
- **THEN** 过滤器 MUST 构造 `UsernamePasswordAuthenticationToken(userId, null, [new SimpleGrantedAuthority("ROLE_USER")])`，并调用 `SecurityContextHolder.getContext().setAuthentication(...)`

#### Scenario: 不覆盖上游认证

- **WHEN** 上游 filter 已经设置了 `SecurityContextHolder.getContext().getAuthentication() != null`
- **THEN** 本过滤器 MUST NOT 覆盖，跳过写入步骤

#### Scenario: finally 清理防线程池泄漏

- **WHEN** 请求处理完毕（正常或异常）
- **THEN** 过滤器 MUST 在 `finally` 里调用 `SecurityContextHolder.clearContext()`，避免 Tomcat / Undertow 线程池复用把身份带到下一个请求

### Requirement: JWT 过滤器 SHALL 支持 EventSource 场景的 query token 兜底

`JwtAuthenticationFilter.extractToken` SHALL 除了 `Authorization: Bearer` 头，还 MUST 兼容 `?access_token=<jwt>` query 参数，专供浏览器 `EventSource` 场景（无法带自定义 header）。

#### Scenario: query token 提取

- **WHEN** 请求 URL 为 `/chat/sse?access_token=xxx.yyy.zzz` 且无 `Authorization` 头
- **THEN** 过滤器 MUST 从 `request.getParameter("access_token")` 提取 token 进行校验

#### Scenario: header 优先

- **WHEN** 请求同时携带 `Authorization: Bearer aaa` 和 `?access_token=bbb`
- **THEN** 过滤器 MUST 优先使用 header 中的 token，忽略 query

#### Scenario: 二者皆无

- **WHEN** 请求既无 `Authorization` 头也无 `?access_token=` query
- **THEN** 过滤器 MUST NOT 写入 SecurityContext，让请求以匿名方式继续（`SecurityConfig` 的兜底规则决定是否放行）
