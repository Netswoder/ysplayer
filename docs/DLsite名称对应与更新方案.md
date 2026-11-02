# DLsite 信息对应与更新

仅针对用户本地作品中存在的 RJ 编号，按需查询公开作品页，缓存社团、多人声优和简体中文标签。项目不分发真实用户收藏清单或全站名录。

社团按公开 RG 编号关联；声优保留作品明确列出的署名，名称不是人物唯一身份。作者、脚本、画师与声优按字段区分。手填内容优先，不用联网结果覆盖已有署名。

访问通过普通 HTTPS 和系统默认网络，读取 robots.txt 并串行限速，每次请求至少间隔 10 秒。成功结果离线复用；失败记录原因并继续下一作品，不无限重试，不绕过登录、验证或地区限制。限速和 robots 规则不代表取得数据再发布授权，使用时仍应遵守服务方条款。

测试 HTML 和 JSON 均为项目自行编写的虚构样本，不对应真实收藏或具体人物。补全行为与数据处理范围见根目录 PRIVACY.md。

独立工具仅供按已知编号的有限查询；输出应保存在被忽略的本地目录，不要提交个人查询结果：

    python tools/dlsite_metadata_probe.py --rj <作品编号> --output artifacts/local-metadata.json
    python -m unittest discover -s tools -p test_dlsite_metadata_probe.py -v
