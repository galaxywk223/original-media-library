# 隐私说明

原片库采用本地优先的数据模型。应用不收集、上传或分析使用数据。

## 本地存储

| 数据 | 默认位置 | 用途 |
| --- | --- | --- |
| SQLite 数据库 | `%LOCALAPPDATA%\OriginalMediaLibrary\library.db` | 任务、设置与媒体索引 |
| 缩略图 | `%LOCALAPPDATA%\OriginalMediaLibrary\thumbnails` | 媒体库预览 |
| 浏览器配置 | `%LOCALAPPDATA%\OriginalMediaLibrary\browser-profile` | 抖音登录状态 |
| 媒体文件 | `%USERPROFILE%\Downloads\原片库` | 下载内容 |

浏览器配置可能包含由抖音和浏览器写入的 Cookie 与站点数据。应用仅在本机使用该独立配置完成用户发起的登录和下载流程。

## 网络请求

网络请求仅在解析分享链接、打开抖音登录页面和下载用户选择的媒体时发生。网络目标由分享链接重定向和抖音页面返回的媒体地址决定。

## 删除

卸载程序保留应用数据。完整删除需要移除 `%LOCALAPPDATA%\OriginalMediaLibrary` 与已配置的下载目录。
