package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/gabrielhidasy/PebblePinManager/server/internal/api"
	"github.com/gabrielhidasy/PebblePinManager/server/internal/config"
	"github.com/gabrielhidasy/PebblePinManager/server/internal/store"
)

func main() {
	healthcheck := flag.Bool("healthcheck", false, "probe local /healthz endpoint and exit 0 if healthy")
	flag.Parse()

	if *healthcheck {
		if err := runHealthcheck(os.Getenv("PIN_SERVER_ADDR")); err != nil {
			fmt.Fprintf(os.Stderr, "healthcheck failed: %v\n", err)
			os.Exit(1)
		}
		os.Exit(0)
	}

	cfg, err := config.LoadFromEnv(os.Getenv)
	if err != nil {
		fmt.Fprintf(os.Stderr, "configuration error: %v\n", err)
		os.Exit(1)
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: cfg.LogLevel,
	}))

	if cfg.WriteToken != "" && len(cfg.WriteToken) < 16 {
		logger.Warn("configured write token is shorter than 16 characters; generate a strong random token (e.g. openssl rand -hex 32)")
	}
	if cfg.ReadToken != "" && len(cfg.ReadToken) < 16 {
		logger.Warn("configured read token is shorter than 16 characters; generate a strong random token (e.g. openssl rand -hex 32)")
	}

	st, err := store.New(cfg.DataFile, cfg.Retention)
	if err != nil {
		logger.Error("failed to initialize pin store", "err", err)
		os.Exit(1)
	}

	auth := api.NewAuthenticator(cfg.WriteToken, cfg.ReadToken)
	handler := api.NewServer(
		st,
		auth,
		api.WithLogger(logger),
		api.WithTrustProxy(cfg.TrustProxy),
	)

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	errCh := make(chan error, 1)
	go func() {
		logger.Info("starting pebble pin companion server", cfg.RedactedSummary()...)
		if serveErr := srv.ListenAndServe(); serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
			errCh <- serveErr
			return
		}
		errCh <- nil
	}()

	select {
	case serveErr := <-errCh:
		if serveErr != nil {
			logger.Error("http server error", "err", serveErr)
			os.Exit(1)
		}
	case <-ctx.Done():
		logger.Info("shutdown signal received")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := srv.Shutdown(shutdownCtx); err != nil {
			logger.Error("graceful shutdown failed", "err", err)
			os.Exit(1)
		}
		logger.Info("server stopped cleanly")
	}
}

func runHealthcheck(addr string) error {
	addr = strings.TrimSpace(addr)
	if addr == "" {
		addr = config.DefaultAddr
	}
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("invalid PIN_SERVER_ADDR %q: %w", addr, err)
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "127.0.0.1"
	}
	target := fmt.Sprintf("http://%s/healthz", net.JoinHostPort(host, port))
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get(target)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	return nil
}
