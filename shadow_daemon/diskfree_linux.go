//go:build linux

package main

import "syscall"

// diskFreeBytes restituisce i byte liberi sulla partizione che contiene il path dato.
func diskFreeBytes(path string) (uint64, error) {
	var stat syscall.Statfs_t
	if err := syscall.Statfs(path, &stat); err != nil {
		return 0, err
	}
	return stat.Bavail * uint64(stat.Bsize), nil
}
