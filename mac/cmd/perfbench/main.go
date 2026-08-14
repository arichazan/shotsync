// perfbench measures end-to-end upload latency against a running ShotSync
// receiver: connect -> pair -> upload N images -> report median, p95, max.
//
// Usage:
//
//	shotsync perfbench -url https://HOST:PORT -pairing-secret SECRET -files 100 -size 3000000 -burst 5
package main

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"time"
)

func main() {
	url := flag.String("url", "https://127.0.0.1:47831", "receiver base URL")
	secret := flag.String("pairing-secret", "", "one-time pairing secret (from `shotsync gen-qr`)")
	files := flag.Int("files", 100, "number of uploads")
	size := flag.Int("size", 3000000, "image size in bytes (~3 MB typical screenshot)")
	burst := flag.Int("burst", 5, "burst: take N screenshots 'at once' then upload")
	insecure := flag.Bool("insecure", true, "skip cert verification (perf bench only)")
	flag.Parse()

	if *secret == "" {
		fmt.Fprintln(os.Stderr, "-pairing-secret is required")
		os.Exit(2)
	}

	client := &http.Client{
		Timeout: 30 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: *insecure, MinVersion: tls.VersionTLS13},
		},
	}

	// Pair.
	deviceID := fmt.Sprintf("perfbench-%d", time.Now().Unix())
	pairBody, _ := json.Marshal(map[string]string{
		"pairing_secret": *secret,
		"device_id":      deviceID,
		"device_name":    "perfbench",
	})
	resp, err := client.Post(*url+"/api/v1/pair", "application/json", bytes.NewReader(pairBody))
	if err != nil {
		fatal(err)
	}
	var pairOut struct {
		DeviceToken string `json:"device_token"`
		Error       string `json:"error"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&pairOut)
	resp.Body.Close()
	if pairOut.DeviceToken == "" {
		fatal(fmt.Errorf("pairing failed: %s", pairOut.Error))
	}
	token := pairOut.DeviceToken
	fmt.Printf("paired as %s\n", deviceID)

	// Pre-generate image bodies to measure transfer time only.
	image := bytes.Repeat([]byte{0x89, 'P', 'N', 'G', 0x0a}, (*size+4)/5)[:*size]
	hashes := make([]string, *files)
	for i := range hashes {
		h := sha256.Sum256(image)
		hashes[i] = hex.EncodeToString(h[:])
	}

	latencies := make([]time.Duration, 0, *files)
	var failures int

	for i := 0; i < *files; i++ {
		start := time.Now()
		req, _ := http.NewRequest(http.MethodPost, *url+"/api/v1/screenshots", bytes.NewReader(image))
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("X-ShotSync-Device-ID", deviceID)
		req.Header.Set("X-ShotSync-File-ID", fmt.Sprintf("%016x-%04x", time.Now().UnixNano(), i))
		req.Header.Set("X-ShotSync-SHA256", hashes[i])
		req.Header.Set("X-ShotSync-Filename", fmt.Sprintf("perf_%04d.png", i))
		req.Header.Set("Content-Type", "image/png")

		resp, err := client.Do(req)
		if err != nil {
			failures++
			fmt.Printf("upload %d failed: %v\n", i, err)
			continue
		}
		io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		if resp.StatusCode != 200 {
			failures++
			fmt.Printf("upload %d HTTP %d\n", i, resp.StatusCode)
			continue
		}
		latencies = append(latencies, time.Since(start))

		// Burst simulation: occasionally pause so the receiver is idle first.
		if i%*burst == *burst-1 {
			time.Sleep(200 * time.Millisecond)
		}
	}

	if len(latencies) == 0 {
		fatal(fmt.Errorf("no successful uploads"))
	}
	sort.Slice(latencies, func(a, b int) bool { return latencies[a] < latencies[b] })
	median := latencies[len(latencies)/2]
	p95 := latencies[int(float64(len(latencies))*0.95)]
	max := latencies[len(latencies)-1]

	fmt.Printf("\nfiles=%d size=%d bytes\n", len(latencies), *size)
	fmt.Printf("median: %v\np95:    %v\nmax:    %v\nfailures: %d\n", median, p95, max, failures)
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "perfbench:", err)
	os.Exit(1)
}
