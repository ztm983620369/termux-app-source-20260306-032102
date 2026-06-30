package cmd

import (
	"fmt"
	"log/slog"

	"charm.land/lipgloss/v2"
	"github.com/charmbracelet/crush/internal/config"
	"github.com/charmbracelet/x/exp/charmtone"
	"github.com/spf13/cobra"
)

var updateProvidersSource string

var updateProvidersCmd = &cobra.Command{
	Use:   "update-providers [path-or-url]",
	Short: "更新模型服务商信息",
	Long:  `从指定本地路径或远程 URL 更新模型服务商信息。`,
	Example: `
# 远程更新 Catwalk 服务商（默认）
crush update-providers

# 从自定义 URL 更新 Catwalk 服务商
crush update-providers https://example.com/providers.json

# 从本地文件更新 Catwalk 服务商
crush update-providers /path/to/local-providers.json

# 使用内置版本更新 Catwalk 服务商
crush update-providers embedded

# 更新 Hyper 服务商信息
crush update-providers --source=hyper

# 从自定义 URL 更新 Hyper
crush update-providers --source=hyper https://hyper.example.com
`,
	RunE: func(cmd *cobra.Command, args []string) error {
		// NOTE(@andreynering): We want to skip logging output do stdout here.
		slog.SetDefault(slog.New(slog.DiscardHandler))

		var pathOrURL string
		if len(args) > 0 {
			pathOrURL = args[0]
		}

		var err error
		switch updateProvidersSource {
		case "catwalk":
			err = config.UpdateProviders(pathOrURL)
		case "hyper":
			err = config.UpdateHyper(pathOrURL)
		default:
			return fmt.Errorf("source %q 无效，必须是 'catwalk' 或 'hyper'", updateProvidersSource)
		}

		if err != nil {
			return err
		}

		// NOTE(@andreynering): This style is more-or-less copied from Fang's
		// error message, adapted for success.
		headerStyle := lipgloss.NewStyle().
			Foreground(charmtone.Butter).
			Background(charmtone.Guac).
			Bold(true).
			Padding(0, 1).
			Margin(1).
			MarginLeft(2).
			SetString("成功")
		textStyle := lipgloss.NewStyle().
			MarginLeft(2).
			SetString(fmt.Sprintf("%s 服务商信息已更新。", updateProvidersSource))

		fmt.Printf("%s\n%s\n\n", headerStyle.Render(), textStyle.Render())
		return nil
	},
}

func init() {
	updateProvidersCmd.Flags().StringVar(&updateProvidersSource, "source", "catwalk", "要更新的服务商来源（catwalk 或 hyper）")
}
