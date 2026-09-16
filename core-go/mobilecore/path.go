package mobilecore

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
)

func safeReadPath(root, requested string) (string, error) {
	if requested == "" || strings.ContainsRune(requested, '\x00') {
		return "", errors.New("invalid empty path")
	}
	normalized := strings.ReplaceAll(requested, "\\", "/")
	if strings.HasPrefix(normalized, "/") {
		return "", errors.New("absolute path is not allowed")
	}
	clean := filepath.Clean(filepath.FromSlash(normalized))
	if clean == "." || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", errors.New("path escapes root")
	}
	rootAbs, err := filepath.Abs(root)
	if err != nil {
		return "", err
	}
	rootReal, err := filepath.EvalSymlinks(rootAbs)
	if err != nil {
		return "", err
	}
	target := filepath.Join(rootReal, clean)
	targetReal, err := filepath.EvalSymlinks(target)
	if err != nil {
		return "", err
	}
	if targetReal != rootReal && !strings.HasPrefix(targetReal, rootReal+string(filepath.Separator)) {
		return "", errors.New("path escapes root through symlink")
	}
	info, err := os.Stat(targetReal)
	if err != nil {
		return "", err
	}
	if !info.Mode().IsRegular() {
		return "", errors.New("requested path is not a regular file")
	}
	return targetReal, nil
}
