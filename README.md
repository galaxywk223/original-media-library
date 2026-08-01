# 原片库

原片库是面向 Windows 本机的抖音原始媒体下载与管理工具。应用通过独立 Chrome 或 Edge 配置保存登录环境，通过本地 Web 界面管理下载任务、图片作品和视频文件。

## 功能

- 从分享文案或链接中批量提取抖音作品。
- 下载原始图片或视频并显示实时进度。
- 自动索引下载目录中的已有媒体文件。
- 按作品分组多图内容，提供搜索、筛选和预览。
- 支持打开文件、资源管理器定位、重命名和移入回收站。
- SQLite 保存任务与媒体元数据，FFmpeg 生成视频缩略图。

## 启动

运行以下命令：

```powershell
.\start.ps1
```

首次启动会安装 Python 和前端依赖并构建 Web 资源。服务仅监听 `127.0.0.1`，随后自动打开默认浏览器。

## 开发

后端开发服务：

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".[test]"
.\.venv\Scripts\python.exe app.py
```

前端开发服务：

```powershell
Set-Location frontend
npm install
npm run dev
```

## 数据目录

| 路径 | 内容 |
| --- | --- |
| `.app-data/library.db` | SQLite 数据库 |
| `.app-data/thumbnails` | 媒体缩略图缓存 |
| `.browser-profile` | 独立浏览器登录环境 |
| `downloads` | 默认下载目录 |
