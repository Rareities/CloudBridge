package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.UUID;

/**
 * Stores a non-secret, durable generation for app-owned rclone config mutations.
 * A revision is only an invalidation token; it never contains config data or a config digest.
 */
public final class ConfigRevisionStore {
	private static final String PREFERENCES = "cloudbridge_rclone_config_revision";
	private static final String KEY_REVISION = "revision";
	private static final String KEY_MUTATION_PENDING = "mutation_pending";
	private static final ProcessState PROCESS_STATE = new ProcessState();

    private ConfigRevisionStore() {
    }

	@Nullable
	public static String current(Context context) {
		return PROCESS_STATE.current(preferences(context));
	}

    /** Marks config mutation in progress before its globally claimed native process starts. */
	public static boolean beginMutation(Context context) {
		return PROCESS_STATE.beginMutation(preferences(context));
	}

    /** Publishes a fresh stable revision before the config mutation's global claim is released. */
	public static boolean finishMutation(Context context) {
		return PROCESS_STATE.finishMutation(preferences(context));
	}

    public static boolean matches(Context context, String expected) {
        if (!ConfigRevisionPolicy.isValidRevision(expected)) {
            return false;
        }
        return ConfigRevisionPolicy.matches(expected, current(context));
    }

	private static SharedPreferences preferences(Context context) {
		return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
	}

	/**
	 * Keeps an in-process fail-closed latch when finalization cannot be durably confirmed.
	 * The already-persisted pending marker is also rewritten best-effort; startup reconciliation
	 * remains responsible for clearing it after the multi-store transaction is recovered.
	 */
	static final class ProcessState {
		private final Object lock = new Object();
		private boolean recoveryRequired;

		@Nullable
		String current(SharedPreferences preferences) {
			synchronized (lock) {
				if (recoveryRequired || preferences.getBoolean(KEY_MUTATION_PENDING, false)) {
					return null;
				}
				String revision = preferences.getString(KEY_REVISION, null);
				if (ConfigRevisionPolicy.isValidRevision(revision)) {
					return revision;
				}
				String initialized = UUID.randomUUID().toString();
				return preferences.edit().putString(KEY_REVISION, initialized).commit()
						? initialized : null;
			}
		}

		boolean beginMutation(SharedPreferences preferences) {
			synchronized (lock) {
				if (recoveryRequired || preferences.getBoolean(KEY_MUTATION_PENDING, false)) {
					return false;
				}
				String revision = preferences.getString(KEY_REVISION, null);
				if (!ConfigRevisionPolicy.isValidRevision(revision)) {
					revision = UUID.randomUUID().toString();
				}
				return preferences.edit()
						.putString(KEY_REVISION, revision)
						.putBoolean(KEY_MUTATION_PENDING, true)
						.commit();
			}
		}

		boolean finishMutation(SharedPreferences preferences) {
			synchronized (lock) {
				if (recoveryRequired || !preferences.getBoolean(KEY_MUTATION_PENDING, false)) {
					return false;
				}
				String previous = preferences.getString(KEY_REVISION, null);
				String next;
				do {
					next = UUID.randomUUID().toString();
				} while (next.equals(previous));
				boolean committed;
				try {
					committed = preferences.edit()
							.putString(KEY_REVISION, next)
							.putBoolean(KEY_MUTATION_PENDING, false)
							.commit();
				} catch (RuntimeException failure) {
					markRecoveryRequired(preferences);
					throw failure;
				}
				if (!committed) {
					markRecoveryRequired(preferences);
					return false;
				}
				return true;
			}
		}

		private void markRecoveryRequired(SharedPreferences preferences) {
			recoveryRequired = true;
			try {
				preferences.edit().putBoolean(KEY_MUTATION_PENDING, true).commit();
			} catch (RuntimeException ignored) {
				// Keep the process-local latch even when storage is too unhealthy to rewrite.
			}
		}
	}
}
