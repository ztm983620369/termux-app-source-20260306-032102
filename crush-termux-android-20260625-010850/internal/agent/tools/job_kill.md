终止后台 shell 进程。

<usage>
- 提供后台 bash 执行返回的 shell ID
- 取消正在运行的进程并清理资源
</usage>

<features>
- 停止长时间运行的后台进程
- 清理已完成的后台 shell
- 立即终止进程
</features>

<tips>
- 需要停止后台进程时使用
- 进程会立即终止（类似 SIGTERM）
- 终止后该 shell ID 将失效
</tips>
