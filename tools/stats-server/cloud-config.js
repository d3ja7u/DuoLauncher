// 由 workbuddy_cloud_service 激活返回的 publicConfig 生成。
// 只含可公开的应用标识（publishableKey 本身无任何权限，服务端还会校验 Origin）。
// 换云服务环境时重新生成，不要手写域名或猜测 key。
window.publicConfig = {
  endpoint: "https://duolauncher-stats.app.workbuddy.host",
  oauthRelayBaseUrl: "https://www.workbuddy.cn/v2/as/genie-baas/oauth",
  publishableKey: "wbpk_XbUMmu9mtBzj4MwApgfuiT_DE6tQ0cz54XFS1J6JvbBWYBzZeFBLEli"
};
