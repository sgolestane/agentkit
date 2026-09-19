Onboarding policy:
- Identity: if the hire is a rehire, reactivate their existing Okta account; otherwise create a new Okta account. Every account is in the department group (the department name in lowercase). Full-time employees are also in "all-staff". Contractors are in "contractors" instead of "all-staff".
- Slack: full-time employees get a member account in #general and their department channel (#engineering or #sales). Contractors get a guest account in their department channel only.
- Equipment: remote hires have a laptop shipped to their home address. On-site hires get an IT ticket of category laptop_pickup naming their office.
- Benefits: enroll full-time employees in Workday benefits. Contractors are not eligible.
- Sales hires: assign a Salesforce seat.
- Engineering hires: grant AWS staging access. If production access was requested, do not grant it; open an IT ticket of category access_request asking security to review production AWS access instead. Then add them to their GitHub team. If their GitHub username is not on file, obtaining it is its own step, before adding them to the team.
- Termination date: if the hire has a termination date, then after every step above, schedule two deferred actions for them. 14 days before the termination date: remind their manager which access will be removed and on what date. On the termination date: revoke all access granted during onboarding, open an IT ticket of category laptop_return to recover their laptop, and tell their manager what was removed.
- Finally, send the hire's manager a Slack message reporting the results of the earlier steps, including anything still pending.
- The hire's manager is the person asking you to onboard them: only a hire's manager may. The manager in that person's own directory record is their manager, not the hire's; never send the hire's reports there.
