package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/aengix/hopper/server/internal/hopper"
	"github.com/aengix/hopper/server/internal/log"
)

var version = "3.1.0"

func main() {
	checkOnly := flag.Bool("check", false, "verify binary runs and exit")
	versionFlag := flag.Bool("version", false, "print version JSON and exit")
	configPath := flag.String("config", "", "hopper.json path")
	readyFile := flag.String("ready-file", "", "write READY line to this path")
	logDir := flag.String("log-dir", "", "directory for daily hopper-YYYY-MM-DD.log files")
	logKeepDays := flag.Int("log-keep-days", log.DefaultKeepDays, "days of daily logs to retain (default 2: today + yesterday)")
	verbose := flag.Bool("verbose", false, "verbose debug logging to stderr")
	flag.Parse()

	log.SetVerbose(*verbose)

	if *checkOnly {
		fmt.Fprintln(os.Stdout, "OK")
		return
	}

	if *versionFlag {
		out, _ := json.Marshal(map[string]string{"version": version})
		fmt.Println(string(out))
		return
	}

	if *logDir != "" {
		if err := log.SetDailyDir(*logDir, *logKeepDays); err != nil {
			fmt.Fprintf(os.Stderr, "log-dir: %v\n", err)
			os.Exit(1)
		}
	}

	if *configPath == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			log.Errorf("home dir: %v", err)
			os.Exit(1)
		}
		*configPath = filepath.Join(home, ".hopper", "hopper.json")
	}

	cfg, err := hopper.LoadConfig(*configPath)
	if err != nil {
		log.Errorf("load config %s: %v", *configPath, err)
		os.Exit(1)
	}

	log.Infof("hopperd starting pid=%d mode=%s version=%s config=%q", os.Getpid(), cfg.Mode(), version, *configPath)

	srv, err := hopper.NewServer(cfg)
	if err != nil {
		log.Errorf("server init: %v", err)
		os.Exit(1)
	}
	if err := srv.Prepare(); err != nil {
		log.Errorf("prepare: %v", err)
		os.Exit(1)
	}
	defer srv.Close()

	ln, port, err := srv.Listen()
	if err != nil {
		log.Errorf("listen failed: %v", err)
		os.Exit(1)
	}
	defer func() {
		log.Infof("hopperd closing listener on port %d", port)
		_ = ln.Close()
	}()

	readyLine := fmt.Sprintf("READY %d\n", port)
	if *readyFile != "" {
		if err := os.WriteFile(*readyFile, []byte(readyLine), 0o600); err != nil {
			log.Errorf("ready-file write %q: %v", *readyFile, err)
			os.Exit(1)
		}
		log.Infof("wrote %s", readyLine[:len(readyLine)-1])
	}
	if _, err := fmt.Fprint(os.Stdout, readyLine); err != nil {
		log.Errorf("stdout ready line: %v", err)
		os.Exit(1)
	}

	sigCh := make(chan os.Signal, 2)
	signal.Notify(sigCh, syscall.SIGHUP, syscall.SIGINT, syscall.SIGTERM)

	reverseStop := make(chan struct{})
	srv.StartReverseDialer(reverseStop)

	go func() {
		sig := <-sigCh
		log.Warnf("hopperd signal %v — shutting down", sig)
		close(reverseStop)
		_ = ln.Close()
		os.Exit(0)
	}()

	if *readyFile == "" {
		go func() {
			n, err := io.Copy(io.Discard, os.Stdin)
			log.Warnf("hopperd stdin closed (read %d bytes): %v", n, err)
			close(reverseStop)
			_ = ln.Close()
		}()
	}

	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Warnf("hopperd accept ended: %v", err)
			return
		}
		log.Infof("hopperd client %s", conn.RemoteAddr())
		go srv.ServeConn(conn)
	}
}
