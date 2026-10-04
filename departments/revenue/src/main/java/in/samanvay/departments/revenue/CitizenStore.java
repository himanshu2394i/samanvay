package in.samanvay.departments.revenue;

import in.samanvay.departments.kit.CitizenDirectory;

/**
 * Who may sign in to this department's citizen login. A citizen signs in with the mobile number registered with the
 * department and a password; the answer is the department's own person ID for them.
 */
interface CitizenStore extends CitizenDirectory {}
