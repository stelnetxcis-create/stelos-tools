package dev.ii.tools.screen;

/**
 * Reed–Solomon erasure coding over GF(2^8), byte-for-byte the code the PC's
 * reed-solomon-erasure (screen.rs, udp.rs) uses: field polynomial 0x11D, generator 2, and
 * the encoding matrix a total×k Vandermonde matrix (v[r][c] = r^c) times the inverse of its
 * top k×k square, so the first k rows are the identity (data shards are sent as they are)
 * and the rest give the parity shards.
 *
 * Only decoding is needed on the phone: with any k of a block's k + m shards, the missing
 * data shards come back. Matrices are small (k ≤ 255) and only built when a shard was lost.
 */
public final class Fec {
    private Fec() {}

    private static final byte[] EXP = new byte[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = (byte) x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    static int mul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]] & 0xFF;
    }

    static int div(int a, int b) {
        if (a == 0) return 0;
        if (b == 0) throw new ArithmeticException("divide by zero");
        int l = LOG[a] - LOG[b];
        if (l < 0) l += 255;
        return EXP[l] & 0xFF;
    }

    /** a^n in the field, as the PC's `galois_8::exp`. */
    static int exp(int a, int n) {
        if (n == 0) return 1;
        if (a == 0) return 0;
        int l = (LOG[a] * n) % 255;
        return EXP[l] & 0xFF;
    }

    /** The (k + m) × k encoding matrix. */
    static int[][] encodingMatrix(int k, int total) {
        int[][] v = new int[total][k];
        for (int r = 0; r < total; r++)
            for (int c = 0; c < k; c++) v[r][c] = exp(r, c);
        int[][] top = new int[k][];
        for (int r = 0; r < k; r++) top[r] = v[r].clone();
        return multiply(v, invert(top));
    }

    static int[][] multiply(int[][] a, int[][] b) {
        int rows = a.length, inner = b.length, cols = b[0].length;
        int[][] out = new int[rows][cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                int acc = 0;
                for (int i = 0; i < inner; i++) acc ^= mul(a[r][i], b[i][c]);
                out[r][c] = acc;
            }
        return out;
    }

    /** Gauss–Jordan inverse of a square matrix in the field. */
    static int[][] invert(int[][] m) {
        int n = m.length;
        int[][] w = new int[n][2 * n];
        for (int r = 0; r < n; r++) {
            System.arraycopy(m[r], 0, w[r], 0, n);
            w[r][n + r] = 1;
        }
        for (int c = 0; c < n; c++) {
            int p = c;
            while (p < n && w[p][c] == 0) p++;
            if (p == n) throw new IllegalArgumentException("singular matrix");
            if (p != c) {
                int[] t = w[p];
                w[p] = w[c];
                w[c] = t;
            }
            int inv = div(1, w[c][c]);
            for (int i = 0; i < 2 * n; i++) w[c][i] = mul(w[c][i], inv);
            for (int r = 0; r < n; r++) {
                if (r == c || w[r][c] == 0) continue;
                int f = w[r][c];
                for (int i = 0; i < 2 * n; i++) w[r][i] ^= mul(f, w[c][i]);
            }
        }
        int[][] out = new int[n][n];
        for (int r = 0; r < n; r++) System.arraycopy(w[r], n, out[r], 0, n);
        return out;
    }

    /**
     * Fills the missing data shards of one block in place.
     *
     * @param shards k + m arrays of equal length; null where the shard never arrived
     * @return false when fewer than k shards are present (the block is lost)
     */
    public static boolean reconstruct(byte[][] shards, int k, int m) {
        int total = k + m;
        int present = 0;
        boolean dataMissing = false;
        for (int i = 0; i < total; i++) {
            if (shards[i] != null) present++;
            else if (i < k) dataMissing = true;
        }
        if (!dataMissing) return true;
        if (present < k) return false;
        int len = -1;
        for (byte[] s : shards) if (s != null) { len = s.length; break; }
        // The first k shards that arrived, and their rows of the encoding matrix.
        int[][] enc = encodingMatrix(k, total);
        int[][] sub = new int[k][];
        byte[][] have = new byte[k][];
        for (int i = 0, n = 0; i < total && n < k; i++) {
            if (shards[i] == null) continue;
            sub[n] = enc[i];
            have[n] = shards[i];
            n++;
        }
        int[][] dec = invert(sub);
        for (int d = 0; d < k; d++) {
            if (shards[d] != null) continue;
            byte[] out = new byte[len];
            int[] row = dec[d];
            for (int j = 0; j < k; j++) {
                int f = row[j];
                if (f == 0) continue;
                byte[] src = have[j];
                int lf = LOG[f];
                for (int b = 0; b < len; b++) {
                    int s = src[b] & 0xFF;
                    if (s != 0) out[b] ^= EXP[lf + LOG[s]];
                }
            }
            shards[d] = out;
        }
        return true;
    }
}
